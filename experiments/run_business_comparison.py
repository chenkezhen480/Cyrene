"""Run independent framework blocks against one isolated fixture; never overwrite evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

SYSTEM_PROMPT = "You are Cyrene, operating an isolated synthetic CRM experiment. Use only authorized tools and current evidence. Never invent business fields, IDs, dates, credentials or policy. The benchmark date is 2026-10-12. Ask for missing required information before writes. Explain access failures accurately. Reply in the user's language."

def businessTasks(taskFile):
    return {**taskFile, 'tasks': [task for task in taskFile['tasks'] if task['category'] not in ('knowledge', 'mixed')]}

def blockOrder(repetitions):
    if repetitions < 1: raise ValueError('repetitions must be positive')
    return [('cyrene_business', 'alibaba_react') if i % 2 == 0 else ('alibaba_react', 'cyrene_business')
            for i in range(repetitions)]

def writeJson(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def run(configPath, repetitions, smoke):
    repo = Path(__file__).resolve().parent.parent
    config = json.loads(configPath.read_text(encoding='utf-8-sig'))
    output = Path(config['outputDirectory'])
    marker = output / ('smoke-order.json' if smoke else 'execution-order.json')
    if marker.exists(): raise ValueError('Execution marker already exists; preserve previous results')
    tasks = businessTasks(json.loads(Path(config['taskFilePath']).read_text(encoding='utf-8-sig')))
    if smoke: tasks['tasks'] = [task for task in tasks['tasks'] if task['id'] == 'business-read-customer-detail']
    taskPath = output / ('smoke-tasks.json' if smoke else 'business-tasks.json')
    writeJson(taskPath, tasks)
    commands = {'cyrene_business': [shutil.which('mvn'), 'test', '-pl', 'harness-agent', '-am',
                '-Dtest=CyreneExperimentIT', '-Dsurefire.failIfNoSpecifiedTests=false', '-DskipITs'],
                'alibaba_react': [shutil.which('java'), '-Dorg.slf4j.simpleLogger.defaultLogLevel=error', '-cp',
                str(repo / 'experiments/alibaba/target/classes') + os.pathsep +
                (repo / 'experiments/alibaba/target/classpath.txt').read_text(encoding='utf-8').strip(), 'experiment.AlibabaExperiment']}
    if any(command[0] is None for command in commands.values()): raise ValueError('Java and Maven must be available')
    execution = {'schemaVersion': 1, 'repetitions': repetitions, 'smoke': smoke, 'taskCount': len(tasks['tasks']),
                 'orderPolicy': 'Alternate first framework per repetition; tasks sequential in each framework block; API cache warmth not fully controlled', 'blocks': []}
    writeJson(marker, execution)
    for repetition, conditions in enumerate(blockOrder(repetitions), 1):
        for condition in conditions:
            blockId = f'{"smoke" if smoke else "round"}-{repetition}-{condition}'
            blockPath = output / blockId
            if blockPath.exists(): raise ValueError('Block output exists: ' + blockId)
            blockPath.mkdir()
            privateSettings = configPath.parent / (blockId + '-settings.json')
            blockConfig = {**config, 'conditions': ['cyrene_business'], 'taskFilePath': str(taskPath),
                           'outputDirectory': str(blockPath), 'repetitions': 1, 'maxTasks': 0,
                           'systemPrompt': SYSTEM_PROMPT, 'maxModelCalls': 10}
            writeJson(privateSettings, blockConfig)
            command = commands[condition] + ([f'-Dcyrene.experiment.config={privateSettings}'] if condition == 'cyrene_business' else [str(privateSettings)])
            print(f'Starting {blockId}', flush=True)
            started = time.monotonic()
            with (blockPath / 'runner.log').open('w', encoding='utf-8') as log:
                completed = subprocess.run(command, cwd=repo, stdout=log, stderr=subprocess.STDOUT)
            block = {'id': blockId, 'repetition': repetition, 'condition': condition, 'exitCode': completed.returncode,
                     'processElapsedMs': round((time.monotonic() - started) * 1000)}
            execution['blocks'].append(block); writeJson(marker, execution)
            print(f'Completed {blockId}: exit={completed.returncode}', flush=True)
            if completed.returncode: raise RuntimeError('Recorded failed experiment block: ' + blockId)
    rows = []
    for block in execution['blocks']:
        for lineNumber, line in enumerate((output / block['id'] / 'raw.jsonl').read_text(encoding='utf-8-sig').splitlines(), 1):
            row = json.loads(line)
            row['blockRepetition'] = block['repetition']; row['sourceRawFile'] = block['id'] + '/raw.jsonl'; row['sourceRawLine'] = lineNumber
            rows.append(row)
    merged = output / ('smoke-raw.jsonl' if smoke else 'raw.jsonl')
    with merged.open('x', encoding='utf-8') as target:
        for row in rows: target.write(json.dumps(row, ensure_ascii=False) + '\n')
    execution['rawSha256'] = hashlib.sha256(merged.read_bytes()).hexdigest()
    execution['trials'] = len(rows); execution['status'] = 'completed'; writeJson(marker, execution)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('settings', type=Path); parser.add_argument('--repetitions', type=int, default=3); parser.add_argument('--smoke', action='store_true')
    args = parser.parse_args()
    try: run(args.settings.resolve(), 1 if args.smoke else args.repetitions, args.smoke)
    except (OSError, ValueError, RuntimeError) as error: parser.exit(1, f'Comparison failed: {type(error).__name__}: {error}\n')
if __name__ == '__main__': main()
