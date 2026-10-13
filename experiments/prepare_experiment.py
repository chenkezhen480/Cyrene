"""Prepare a private configuration and an isolated Docker experiment project."""
import argparse
import hashlib
import json
import re
import secrets
import subprocess
from pathlib import Path


def parsePublishedPort(address):
    match = re.fullmatch(r'127\.0\.0\.1:(\d+)', address.strip())
    if not match or not 1 <= int(match[1]) <= 65535:
        raise ValueError('Experiment service must have a valid loopback port')
    return int(match[1])


def readModelAssignments(content):
    result = {}
    for lineNumber, line in enumerate(content.splitlines(), 1):
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        match = re.fullmatch(r'\s*([A-Za-z][A-Za-z0-9.]*)\s*=\s*(.*?)\s*', line)
        if not match or match[1] in result:
            raise ValueError(f'Invalid or duplicate model assignment at line {lineNumber}')
        value = match[2]
        result[match[1]] = json.loads(value) if value.startswith('"') else value
    return result


def sanitizedModelMetadata(config):
    publicKeys = {'chatProvider': 'chat.provider', 'chatModel': 'chat.model',
                  'chatTemperature': 'chat.temperature', 'chatMaxTokens': 'chat.maxTokens',
                  'chatApiFormat': 'chat.apiFormat', 'embeddingProvider': 'embedding.provider',
                  'embeddingModel': 'embedding.model', 'embeddingDimension': 'embedding.dimension',
                  'rerankProvider': 'rerank.provider', 'rerankModel': 'rerank.model',
                  'routingProvider': 'routing.provider', 'routingModel': 'routing.model'}
    return {name: config.get(key, '') for name, key in publicKeys.items()}


def writeJson(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def runCommand(arguments, capture=False):
    return subprocess.run(arguments, check=True, text=True, encoding='utf-8',
                          stdout=subprocess.PIPE if capture else None)


def prepareExperiment(repoRoot, runId, modelConfigPath, repetitions, maxTasks, start):
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,60}', runId):
        raise ValueError('runId must contain lowercase letters, digits and hyphens')
    outputDirectory = repoRoot / 'docs' / 'experiments' / runId
    runtimeDirectory = repoRoot / 'docs' / 'experiments' / '.runtime' / runId
    if runtimeDirectory.exists() or outputDirectory.exists():
        raise ValueError('Run directory already exists; use a new runId to preserve earlier results')
    sourceBytes = modelConfigPath.read_bytes()
    modelSettings = readModelAssignments(sourceBytes.decode('utf-8-sig'))
    if not modelSettings.get('chat.provider') or not modelSettings.get('chat.apiKey'):
        raise ValueError('The selected model.conf must configure a chat provider and key')
    if not modelSettings.get('embedding.provider') or not modelSettings.get('embedding.apiKey'):
        raise ValueError('Wiki experiments require the configured embedding provider')
    runtimeDirectory.mkdir(parents=True)
    outputDirectory.mkdir(parents=True)
    privateModelPath = runtimeDirectory / 'model.conf'
    # Disable unrelated paid/modal capabilities in the private copy only.
    for capability in ('vision', 'voice', 'imageGeneration', 'videoGeneration', 'realtime', 'smallTask'):
        modelSettings[f'{capability}.provider'] = 'none'
    privateModelPath.write_text(''.join(f'{key}={json.dumps(value, ensure_ascii=False)}\n'
                                      for key, value in modelSettings.items()), encoding='utf-8')
    tokensPath = runtimeDirectory / 'fixture-tokens.json'
    writeJson(tokensPath, {principal: secrets.token_urlsafe(32)
                           for principal in ('ownerAlpha', 'ownerBeta', 'admin')})
    for name in ('artifacts', 'knowledge-uploads', 'graph-schemas', 'skills', 'fixture'):
        (runtimeDirectory / name).mkdir()
    writeJson(runtimeDirectory / 'mcp.json', {'mcpServers': {}})
    databasePassword = secrets.token_urlsafe(32)
    parserToken = secrets.token_urlsafe(32)
    projectName = f'cyrene-experiment-{runId}'
    environmentPath = runtimeDirectory / 'compose.env'
    environmentPath.write_text('\n'.join([
        f'EXPERIMENT_RUNTIME_DIR={runtimeDirectory.as_posix()}',
        f'EXPERIMENT_MYSQL_PASSWORD={databasePassword}',
        f'EXPERIMENT_DOCUMENT_PARSER_TOKEN={parserToken}',
        'EXPERIMENT_MINIO_USER=minioadmin',
        'EXPERIMENT_MINIO_PASSWORD=minioadmin',
    ]) + '\n', encoding='utf-8')
    composeCommand = ['docker', 'compose', '-f', str(repoRoot / 'experiments' / 'compose.yaml'),
                      '--env-file', str(environmentPath), '-p', projectName]
    revision = runCommand(['git', '-C', str(repoRoot), 'rev-parse', 'HEAD'], True).stdout.strip()
    metadata = {'runId': runId, 'sourceCommit': revision,
                'sourceModelConfigSha256': hashlib.sha256(sourceBytes).hexdigest(),
                'experimentModelConfigSha256': hashlib.sha256(privateModelPath.read_bytes()).hexdigest(),
                'models': sanitizedModelMetadata(modelSettings),
                'dockerProject': projectName, 'repetitions': repetitions, 'maxTasks': maxTasks,
                'conditions': ['wiki_on', 'wiki_off', 'bare_langchain4j'], 'jevBenchmark': False,
                'status': 'prepared', 'originalModelFileChangedByPreparation': False}
    writeJson(outputDirectory / 'environment.json', metadata)
    if not start:
        print(f'Prepared private configuration; results: {outputDirectory}')
        return
    runCommand(composeCommand + ['up', '-d', '--build', '--wait', '--wait-timeout', '240'])
    ports = {service: parsePublishedPort(runCommand(composeCommand + ['port', service, str(port)], True).stdout)
             for service, port in {'mysql': 3306, 'milvus': 19530, 'redis': 6379,
                                   'document-parser': 8082, 'business': 8080}.items()}
    collectionSuffix = re.sub('[^a-zA-Z0-9_]', '_', runId)
    envOverrides = {
        'HARNESS_CONFIG_MODEL_FILE': str(privateModelPath),
        'HARNESS_CONFIG_MODEL_READ_GROUP': '',
        'HARNESS_AUDIT_STORE': 'mysql', 'HARNESS_MEMORY_STORE': 'mysql',
        'HARNESS_AUDIT_DB_URL': f'jdbc:mysql://127.0.0.1:{ports["mysql"]}/agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
        'HARNESS_AUDIT_DB_USER': 'root', 'HARNESS_AUDIT_DB_PASS': databasePassword,
        'HARNESS_MEMORY_REDIS_URL': f'redis://127.0.0.1:{ports["redis"]}',
        'HARNESS_MEMORY_REDIS_PASSWORD': '',
        'HARNESS_MEMORY_REDIS_KEY_PREFIX': f'cyrene:experiment:{runId}:',
        'HARNESS_RAG_PROVIDER': 'milvus', 'HARNESS_RAG_URL': f'http://127.0.0.1:{ports["milvus"]}',
        'HARNESS_RAG_API_KEY': '', 'HARNESS_RAG_DATABASE': 'default',
        'HARNESS_RAG_COLLECTION': f'experiment_documents_{collectionSuffix}',
        'HARNESS_KNOWLEDGE_CATALOG_COLLECTION': f'experiment_catalog_{collectionSuffix}',
        'HARNESS_MEMORY_USER_KNOWLEDGE_COLLECTION': f'experiment_episodes_{collectionSuffix}',
        'HARNESS_MEMORY_OPERATION_KNOWLEDGE_COLLECTION': f'experiment_playbooks_{collectionSuffix}',
        'HARNESS_DOCUMENT_PARSER_URL': f'http://127.0.0.1:{ports["document-parser"]}',
        'HARNESS_DOCUMENT_PARSER_TOKEN': parserToken,
        'HARNESS_GRAPH_PROVIDER': 'none', 'HARNESS_SEARCH_PROVIDERS': '',
        'HARNESS_TOOL_WEB_SEARCH_ENABLED': 'false', 'HARNESS_TOOL_URL_READER_ENABLED': 'false',
        'HARNESS_TOOL_BROWSER_ENABLED': 'false', 'HARNESS_TOOL_FFMPEG_ENABLED': 'false',
        'HARNESS_SHELL_ENABLED': 'false', 'HARNESS_PROJECT_DISCOVERY_ENABLED': 'false',
        'HARNESS_ARTIFACT_DIR': str(runtimeDirectory / 'artifacts'),
        'HARNESS_KNOWLEDGE_UPLOAD_DIR': str(runtimeDirectory / 'knowledge-uploads'),
        'HARNESS_GRAPH_SCHEMA_DIR': str(runtimeDirectory / 'graph-schemas'),
        'HARNESS_MCP_CONFIG_FILE': str(runtimeDirectory / 'mcp.json'),
        'HARNESS_SKILL_DIR': str(runtimeDirectory / 'skills'),
        'HARNESS_PROJECT_APIS_CONFIG_FILE': str(runtimeDirectory / 'project-apis.json'),
    }
    settings = {'envOverrides': envOverrides, 'modelConfigPath': str(privateModelPath),
                'fixtureUrl': f'http://127.0.0.1:{ports["business"]}',
                'fixtureTokensPath': str(tokensPath),
                'taskFilePath': str(repoRoot / 'experiments' / 'tasks.json'),
                'corpusDirectory': str(repoRoot / 'experiments' / 'corpus'),
                'outputDirectory': str(outputDirectory), 'repetitions': repetitions,
                'maxTasks': maxTasks, 'conditions': metadata['conditions']}
    writeJson(runtimeDirectory / 'settings.json', settings)
    metadata['status'] = 'containers_healthy'
    metadata['ports'] = ports
    writeJson(outputDirectory / 'environment.json', metadata)
    if modelConfigPath.read_bytes() != sourceBytes:
        raise RuntimeError('Source model.conf changed during preparation; review before running experiments')
    print(f'Healthy isolated project: {projectName}')
    print(f'Private runner configuration: {runtimeDirectory / "settings.json"}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--model-config', type=Path)
    parser.add_argument('--repetitions', type=int, default=1)
    parser.add_argument('--max-tasks', type=int, default=0)
    parser.add_argument('--start', action='store_true')
    args = parser.parse_args()
    if args.repetitions < 1 or args.max_tasks < 0:
        parser.error('repetitions must be positive and max-tasks nonnegative')
    repoRoot = Path(__file__).resolve().parent.parent
    try:
        prepareExperiment(repoRoot, args.run_id, (args.model_config or repoRoot / 'data' / 'model.conf').resolve(),
                          args.repetitions, args.max_tasks, args.start)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'Experiment preparation failed: {type(error).__name__}: {error}\n')


if __name__ == '__main__':
    main()
