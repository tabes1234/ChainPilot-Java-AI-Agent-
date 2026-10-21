export const DEMO_SCENARIO = {
	id: 'supplier-delay',
	title: '关键供应商交付延迟 + MCU 库存缺口',
	question:
		'供应商 SUP-ALPHA 出现交付延迟，SKU-MCU-001 库存不足，部分订单可能延期。请分析受影响订单、风险等级和主要证据，并给出可执行的处置建议。',
	decisionQuestion:
		'供应商 SUP-ALPHA 交付延迟且 SKU-MCU-001 库存不足。请比较补货 50、100、200 件三个方案，结合风险、库存缺口和交付影响给出推荐。',
	simulationSku: 'SKU-MCU-001',
	simulationQuantity: 100,
	comparisonCandidates: ['50', '100', '200'],
	context: [
		'重点供应商：SUP-ALPHA（交付延迟）',
		'重点物料：SKU-MCU-001（库存不足）',
		'关注订单：存在延期/缺货风险的订单',
	],
	next: '先运行风险分析，再把问题带入自然语言决策，最后在处置工作台完成模拟与沙箱演练。',
} as const;

export function isDemoScenario(value: string | null): boolean {
	return value === DEMO_SCENARIO.id || value === '1' || value === 'true';
}
