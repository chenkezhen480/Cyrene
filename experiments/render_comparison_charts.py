"""Render publication charts from reviewed experiment statistics (requires matplotlib)."""
import argparse
import json
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties
from matplotlib.ticker import FuncFormatter, MaxNLocator


def bars(axis, labels, values, colors, title, unit, valueLabels):
    axis.barh(range(len(labels)), values, color=colors, height=0.52)
    axis.invert_yaxis()
    axis.set_yticks(range(len(labels)), labels, fontsize=10)
    axis.set_xlim(0, max(values) * 1.3)
    axis.set_title(title, loc='left', fontsize=13, fontweight='bold', pad=19)
    axis.set_xlabel(unit, fontsize=9, color='#64748b')
    axis.xaxis.set_major_locator(MaxNLocator(4))
    axis.xaxis.set_major_formatter(FuncFormatter(lambda value, _: f'{value:g}' if unit.startswith(('秒', 'seconds')) else f'{value:,.0f}'))
    if unit.startswith('%'):
        axis.set_xlim(0, 112)
        axis.set_xticks([0, 50, 100])
    axis.grid(axis='x', color='#e2e8f0', linewidth=0.7)
    axis.set_axisbelow(True)
    axis.tick_params(axis='both', length=0, labelcolor='#475569')
    for spine in axis.spines.values():
        spine.set_visible(False)
    for position, (value, label) in enumerate(zip(values, valueLabels)):
        axis.text(value + max(values) * 0.025, position, label, va='center', fontsize=11, color='#0f172a', fontweight='bold')


def render(repo, language, fontPath):
    records = repo / 'docs' / 'experiments'
    business = json.loads((records / '20261013-business-compare/analysis-reviewed.json').read_text(encoding='utf-8'))
    wiki = json.loads((records / '20261013-controlled/analysis-reviewed.json').read_text(encoding='utf-8'))
    assert all(business[c]['trials'] == 33 for c in ('cyrene_business', 'alibaba_react'))
    knowledge = [wiki[c]['categories']['knowledge'] for c in ('wiki_on', 'wiki_off', 'bare_langchain4j')]
    assert all(s['trials'] == s['passed'] == 4 and s['inputTokens']['observedTrials'] == 4 for s in knowledge)
    chinese = language == 'zh'
    if fontPath:
        font = FontProperties(fname=str(fontPath))
        matplotlib.font_manager.fontManager.addfont(str(fontPath))
        plt.rcParams['font.family'] = font.get_name()
    plt.rcParams.update({'axes.unicode_minus': False, 'svg.fonttype': 'path', 'font.size': 10})
    figure, axes = plt.subplots(2, 3, figsize=(15, 9), facecolor='#ffffff')
    figure.subplots_adjust(left=0.115, right=0.97, top=0.77, bottom=0.20, hspace=1.85, wspace=0.72)
    for column in range(3):
        left = 0.115 + column * 0.32
        axes[0, column].set_position([left, 0.615, 0.19, 0.13])
        axes[1, column].set_position([left, 0.22, 0.19, 0.18])
    blue, gray, amber = '#2563eb', '#64748b', '#d97706'
    figure.text(0.045, 0.945, 'Cyrene 实验对比' if chinese else 'Cyrene · Experimental comparisons', fontsize=23, fontweight='bold', color='#0f172a')
    figure.text(0.045, 0.905, '2026-10-13  ·  实测数据  ·  各实验单独解释' if chinese else '2026-10-13  ·  Measured results  ·  Separate cohorts', fontsize=11, color='#64748b')
    figure.text(0.045, 0.848, '01  已有系统业务接入：11 题 × 3 轮 × 2 框架，共 66 次' if chinese else '01  Business API integration: 11 tasks × 3 repeats × 2 frameworks = 66 trials', fontsize=14, fontweight='bold', color='#0f172a')
    figure.text(0.045, 0.815, '业务目标两组均 33/33；完整回复另做事实复核。相同模型及宿主，各组保留原生提示与工具 Schema。' if chinese else 'Business outcomes: both 33/33. Full answers separately reviewed. Same model and host; native prompts and schemas retained.', fontsize=10, color='#475569')
    labels = ['Cyrene', 'Alibaba ReAct']
    selected = [business[c] for c in ('cyrene_business', 'alibaba_react')]
    values = [s['passed'] / s['trials'] * 100 for s in selected]
    bars(axes[0, 0], labels, values, [blue, gray], '完整回复事实正确' if chinese else 'Full-answer factual accuracy', '% · 越高越好' if chinese else '% · higher is better', [f"{s['passed']}/33" for s in selected])
    values = [s['durationMs']['meanObserved'] / 1000 for s in selected]
    bars(axes[0, 1], labels, values, [blue, gray], '平均任务耗时' if chinese else 'Mean task latency', '秒 · 越低越好' if chinese else 'seconds · lower is better', [f'{v:.2f}' for v in values])
    values = [s['inputTokens']['meanObserved'] for s in selected]
    bars(axes[0, 2], labels, values, [blue, gray], '平均输入 Token' if chinese else 'Mean input tokens', 'Token / 任务 · 越低越好' if chinese else 'tokens / task · lower is better', [f'{v:,.0f}' for v in values])
    figure.text(0.045, 0.51, '02  Wiki 消融：4 份合成文档，4 道纯知识题，每组 1 轮' if chinese else '02  Wiki ablation: 4 synthetic documents, 4 knowledge-only tasks, 1 repeat per condition', fontsize=14, fontweight='bold', color='#0f172a')
    figure.text(0.045, 0.477, 'Wiki 输入比关闭 Wiki 低 8.7%；本轮未观察到提速。原生 LangChain4j 复用 Cyrene 工具与权限。' if chinese else 'Wiki used 8.7% fewer input tokens than Wiki off; no speedup observed. Native LangChain4j shares Cyrene tools and authorization.', fontsize=10, color='#475569')
    labels = ['Wiki 开启', 'Wiki 关闭', '原生 LC4j*'] if chinese else ['Wiki on', 'Wiki off', 'Native LC4j*']
    colors = [blue, amber, gray]
    bars(axes[1, 0], labels, [100] * 3, colors, '知识题事实正确' if chinese else 'Knowledge-task accuracy', '% · 越高越好' if chinese else '% · higher is better', ['4/4'] * 3)
    values = [s['durationMs']['meanObserved'] / 1000 for s in knowledge]
    bars(axes[1, 1], labels, values, colors, '平均任务耗时' if chinese else 'Mean task latency', '秒 · 越低越好' if chinese else 'seconds · lower is better', [f'{v:.2f}' for v in values])
    values = [s['inputTokens']['meanObserved'] for s in knowledge]
    bars(axes[1, 2], labels, values, colors, '平均输入 Token' if chinese else 'Mean input tokens', 'Token / 任务 · 越低越好' if chinese else 'tokens / task · lower is better', [f'{v:,.0f}' for v in values])
    footnotes = ['限于一套合成 CRM 和小样本文档；重复题不等于独立场景。LLM 输入用量不等于系统总费用。',
                 'Provider 缓存与原生提示未完全控制；不宣称跨产品全面优胜。Jev 未做独立测量。',
                 '* LC4j = LangChain4j；该组只作共享工具的循环基线。原始记录与复核方法见 docs/experiments。'] if chinese else [
                 'One synthetic CRM and small document corpus. Repeats are not independent scenarios. LLM input usage is not total system cost.',
                 'Provider cache warmth and native prompts are not fully controlled. No product-wide superiority claim. Jev was not benchmarked.',
                 '* LC4j = LangChain4j: a loop baseline with shared tools. Raw data and review methodology: docs/experiments.']
    for index, text in enumerate(footnotes):
        figure.text(0.045, 0.11 - index * 0.027, text, fontsize=9, color='#64748b')
    target = records / 'assets'
    target.mkdir(exist_ok=True)
    for extension in ('png', 'svg'):
        path = target / f'experiment-comparison-{language}.{extension}'
        figure.savefig(path, dpi=160, facecolor='white')
        if extension == 'svg':
            path.write_text('\n'.join(line.rstrip() for line in path.read_text(encoding='utf-8').splitlines()) + '\n', encoding='utf-8')
    plt.close(figure)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--font', type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    for language in ('zh', 'en'):
        render(repo, language, args.font)
    print('Rendered Chinese and English charts directly from reviewed statistics')


if __name__ == '__main__':
    main()
