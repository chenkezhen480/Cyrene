"""Render publication charts from reviewed experiment statistics (requires matplotlib)."""
import argparse
import json
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties
from matplotlib.patches import FancyBboxPatch
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
    parser.add_argument('--integration-only', action='store_true')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    for language in ('zh', 'en'):
        if not args.integration_only:
            render(repo, language, args.font)
        renderIntegration(repo, language, args.font)
    print('Rendered Chinese and English integration overview; measured statistics preserved')


def renderIntegration(repo, language, fontPath):
    chinese = language == 'zh'
    if fontPath:
        matplotlib.font_manager.fontManager.addfont(str(fontPath))
        plt.rcParams['font.family'] = FontProperties(fname=str(fontPath)).get_name()
    plt.rcParams['svg.fonttype'] = 'path'
    figure, axis = plt.subplots(figsize=(13, 7), facecolor='white')
    axis.set_position([0, 0, 1, 1])
    axis.axis('off')
    figure.text(0.055, 0.915, '让已有系统快速获得 Agent 入口' if chinese else 'Give an existing system an Agent interface', fontsize=23, fontweight='bold', color='#0f172a')
    figure.text(0.055, 0.86, 'Cyrene 将通用接入与运行能力封装为可部署服务；接入方配置接口、身份与业务范围。' if chinese else 'Cyrene packages integration and runtime facilities as a deployable service; integrators configure APIs, identity, and scope.', fontsize=11, color='#475569')
    labels = [('Cyrene · 复用现成能力', '#2563eb', '#eff6ff'), ('Alibaba · 本次原生工具路线', '#64748b', '#f8fafc')] if chinese else [
        ('Cyrene · reuse integrated facilities', '#2563eb', '#eff6ff'), ('Alibaba · native tool route tested', '#64748b', '#f8fafc')]
    left = ['扫描 OpenAPI / Swagger\n生成接口参数 Schema', '审核接口与认证配置\n复用通用 HTTP 工具及凭证注入', '接入现有 chat 服务\n复用 ReAct、会话与 Trace'] if chinese else [
        'Scan OpenAPI / Swagger\nGenerate endpoint parameter schemas', 'Review APIs and authentication\nReuse HTTP tools and credential propagation', 'Connect to the existing chat service\nReuse ReAct, sessions, and tracing']
    right = ['编写五个注解工具\n注册为 ToolCallbacks', '编写独立 HTTP 适配\n通过 ToolContext 获取用户凭证', '组装官方 ReactAgent\n配置模型、工具与 MemorySaver'] if chinese else [
        'Implement five annotated tools\nRegister ToolCallbacks', 'Implement an independent HTTP adapter\nRead credentials from ToolContext', 'Assemble the official ReactAgent\nConfigure model, tools, and MemorySaver']
    for column, ((title, color, background), cells) in enumerate(zip(labels, (left, right))):
        x = 0.055 + column * 0.475
        figure.text(x, 0.765, title, fontsize=15, fontweight='bold', color=color)
        for index, text in enumerate(cells):
            y = 0.57 - index * 0.17
            axis.add_patch(FancyBboxPatch((x, y), 0.415, 0.125, boxstyle='round,pad=0.012', facecolor=background, edgecolor='#dbeafe' if column == 0 else '#e2e8f0', linewidth=1, transform=axis.transAxes))
            figure.text(x + 0.025, y + 0.062, text, va='center', fontsize=12, linespacing=1.7, color='#0f172a')
    figure.text(0.055, 0.145, '接入后业务目标：两组均 33/33。完整回复事实复核：Cyrene 31/33，Alibaba 32/33。' if chinese else 'Business outcomes: both 33/33. Full-answer factual review: Cyrene 31/33; Alibaba 32/33.', fontsize=11, color='#0f172a')
    figure.text(0.055, 0.10, '这是本次接入方式对照，完整人工接入工时未测。Alibaba 可采用其他集成路线；本轮运行效率更好。' if chinese else 'Routes tested, not measured developer onboarding time. Alibaba offers other routes and had better runtime metrics here.', fontsize=10, color='#64748b')
    figure.text(0.055, 0.065, '原始运行对照、计时范围与复核记录均保留在 README 及 docs/experiments。' if chinese else 'Runtime comparisons, timing scope, and reviewed records remain in the README and docs/experiments.', fontsize=10, color='#64748b')
    target = repo / 'docs/experiments/assets'
    target.mkdir(exist_ok=True)
    for extension in ('png', 'svg'):
        path = target / f'integration-overview-{language}.{extension}'
        figure.savefig(path, dpi=160, facecolor='white')
        if extension == 'svg':
            path.write_text('\n'.join(line.rstrip() for line in path.read_text(encoding='utf-8').splitlines()) + '\n', encoding='utf-8')
    plt.close(figure)


if __name__ == '__main__':
    main()
