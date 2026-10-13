"""Summarize observed experiment rows without concealing failed tasks or missing usage."""
import argparse
import json
import math
from pathlib import Path


def distribution(values):
    values = sorted(values)
    if not values:
        return {'observedTrials': 0, 'sumObserved': None, 'meanObserved': None, 'p50': None, 'p95': None}
    return {'observedTrials': len(values), 'sumObserved': sum(values),
            'meanObserved': sum(values) / len(values),
            'p50': values[math.ceil(len(values) * 0.5) - 1],
            'p95': values[math.ceil(len(values) * 0.95) - 1]}


def callUsage(row, suffix):
    trace = row.get('trace')
    metadata = trace.get('metadata', {}) if trace else {}
    callCount = int(metadata.get('llmUsageCallCount', 0))
    if callCount < 0:
        raise ValueError('Negative model call count')
    values = [int(metadata[f'llmUsageCall{index}{suffix}'])
              for index in range(1, callCount + 1)
              if metadata.get(f'llmUsageCall{index}{suffix}') is not None]
    if any(value < 0 for value in values):
        raise ValueError(f'Negative API usage for {suffix}')
    coverage = 'complete' if callCount > 0 and len(values) == callCount else 'partial' if values else 'unknown'
    return {'coverage': coverage, 'value': sum(values) if values else None,
            'observedCallIndices': {index for index in range(1, callCount + 1)
                                    if metadata.get(f'llmUsageCall{index}{suffix}') is not None},
            'observedCalls': len(values), 'modelCalls': callCount}


def usageDistribution(usages):
    result = distribution([usage['value'] for usage in usages if usage['coverage'] == 'complete'])
    result.update(partialTrials=sum(usage['coverage'] == 'partial' for usage in usages),
                  unknownTrials=sum(usage['coverage'] == 'unknown' for usage in usages),
                  observedCalls=sum(usage['observedCalls'] for usage in usages),
                  modelCalls=sum(usage['modelCalls'] for usage in usages))
    return result


def summarize(rows):
    result = {}
    for condition in sorted({row['condition'] for row in rows}):
        trials = [row for row in rows if row['condition'] == condition]
        passed = sum(row['status'] == 'completed' and row['passed'] is True for row in trials)
        metrics = {'trials': len(trials), 'passed': passed, 'failed': len(trials) - passed,
                   'successRate': passed / len(trials),
                   'durationMs': distribution([row['durationMs'] for row in trials if row.get('durationMs') is not None]),
                   'failureTaskIds': sorted({row['taskId'] for row in trials
                                             if row['status'] != 'completed' or row['passed'] is not True}),
                   'categories': {}}
        usageByLabel = {label: [callUsage(row, suffix) for row in trials]
                        for label, suffix in (('inputTokens', 'InputTokens'), ('outputTokens', 'OutputTokens'),
                                              ('cachedInputTokens', 'CachedInputTokens'))}
        for label, usages in usageByLabel.items():
            metrics[label] = usageDistribution(usages)
        totalUsage = []
        for inputUsage, outputUsage in zip(usageByLabel['inputTokens'], usageByLabel['outputTokens']):
            complete = inputUsage['coverage'] == outputUsage['coverage'] == 'complete'
            totalUsage.append({'coverage': 'complete' if complete else
                               'unknown' if inputUsage['value'] is outputUsage['value'] is None else 'partial',
                               'value': inputUsage['value'] + outputUsage['value'] if complete else None,
                               'observedCalls': len(inputUsage['observedCallIndices'] & outputUsage['observedCallIndices']),
                               'modelCalls': inputUsage['modelCalls']})
        metrics['totalTokens'] = usageDistribution(totalUsage)
        for category in sorted({row.get('category', 'unknown') for row in trials}):
            categoryRows = [row for row in trials if row.get('category', 'unknown') == category]
            metrics['categories'][category] = {'trials': len(categoryRows),
                'passed': sum(row['status'] == 'completed' and row['passed'] is True for row in categoryRows)}
            metrics['categories'][category]['durationMs'] = distribution(
                [row['durationMs'] for row in categoryRows if row.get('durationMs') is not None])
            for label, suffix in (('inputTokens', 'InputTokens'), ('outputTokens', 'OutputTokens')):
                metrics['categories'][category][label] = usageDistribution([callUsage(row, suffix) for row in categoryRows])
        result[condition] = metrics
    return result


def formatted(value, divisor=1):
    return '未观测' if value is None else f'{value / divisor:.1f}'


def writeReport(result, outputPath):
    lines = ['# Cyrene 实验结果', '',
             '以下统计包含失败任务；Token 均值仅纳入全部模型调用都有对应 API 用量的任务。缺失或部分用量保持未知，不按零计算。p50/p95 使用观测样本的最近秩。', '',
             '| 条件 | 通过/总数 | 成功率 | 平均耗时 s | p95 s | LLM 输入 Token 均值 | LLM 输出 Token 均值 |',
             '|---|---:|---:|---:|---:|---:|---:|']
    for condition, stats in result.items():
        lines.append(f'| {condition} | {stats["passed"]}/{stats["trials"]} | {stats["successRate"]:.1%} | '
                     f'{formatted(stats["durationMs"]["meanObserved"], 1000)} | '
                     f'{formatted(stats["durationMs"]["p95"], 1000)} | '
                     f'{formatted(stats["inputTokens"]["meanObserved"])} | '
                     f'{formatted(stats["outputTokens"]["meanObserved"])} |')
    lines += ['', '## 用量覆盖与失败样本', '']
    for condition, stats in result.items():
        lines.append(f'- {condition}：输入完整 {stats["inputTokens"]["observedTrials"]}/{stats["trials"]}、'
                     f'部分 {stats["inputTokens"]["partialTrials"]}、未知 {stats["inputTokens"]["unknownTrials"]}；'
                     f'输出完整 {stats["outputTokens"]["observedTrials"]}/{stats["trials"]}、'
                     f'部分 {stats["outputTokens"]["partialTrials"]}、未知 {stats["outputTokens"]["unknownTrials"]}；'
                     f'失败任务：{", ".join(stats["failureTaskIds"]) or "无"}。')
    lines += ['', '## 知识任务与知识结合业务任务', '',
              '业务读写题不一定调用知识检索，Wiki 用量判断应单独看以下题类。', '',
              '| 条件 | 题类 | 通过/总数 | 平均耗时 s | LLM 输入 Token 均值 | 输入完整观测 |',
              '|---|---|---:|---:|---:|---:|']
    for condition, stats in result.items():
        for category, categoryStats in stats['categories'].items():
            if category not in ('knowledge', 'mixed'):
                continue
            lines.append(f'| {condition} | {category} | {categoryStats["passed"]}/{categoryStats["trials"]} | '
                         f'{formatted(categoryStats["durationMs"]["meanObserved"], 1000)} | '
                         f'{formatted(categoryStats["inputTokens"]["meanObserved"])} | '
                         f'{categoryStats["inputTokens"]["observedTrials"]}/{categoryStats["trials"]} |')
    lines += ['', '## 解释边界', '',
              '- 这是合成制度和受控宿主的预实验；有限任务和重复次数不支持生产泛化或同类项目胜负结论。',
              '- 本轮没有 Jev 独立速度实验，也未把官方速度数据计为 Cyrene 实测。',
              '- Token 来自逐次 API 用量；总量仅在输入、输出全部观测时相加。框架缺省总量零值不作为 API 观测。没有可核验价格或账单时不换算金额。',
              '- LLM 用量与 Embedding/重排用量分别看待；缺失后者时不能据此宣称整个知识系统的总费用下降。',
              '- 知识摄取与基础设施成本另见 setup.json；若未观测摄取用量，不计为零。', '']
    outputPath.write_text('\n'.join(lines), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results', type=Path)
    args = parser.parse_args()
    try:
        rows = [json.loads(line) for line in args.results.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
        if not rows:
            raise ValueError('Results contain no trials')
        result = summarize(rows)
        outputDirectory = args.results.resolve().parent
        (outputDirectory / 'analysis.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        writeReport(result, outputDirectory / 'results.md')
        print(json.dumps({condition: {'passed': stats['passed'], 'trials': stats['trials']}
                          for condition, stats in result.items()}, ensure_ascii=False))
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f'Analysis failed: {type(error).__name__}: {error}\n')


if __name__ == '__main__':
    main()
