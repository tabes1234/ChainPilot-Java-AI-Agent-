/** Real, backend-backed entry points shown on the brand home page. */
export type HomeFeature = {
	label: string;
	description: string;
	href: string;
};

export type HomeFeatureGroup = {
	title: string;
	description: string;
	features: HomeFeature[];
};

export const HOME_FEATURE_GROUPS: HomeFeatureGroup[] = [
	{
		title: '供应链工作流',
		description: '从风险发现到处置演练的完整链路',
		features: [
			{ label: '供应链工作台', description: '订单、库存与供应商风险分析', href: '/supply-chain' },
			{ label: '智能决策', description: '候选方案比较与确定性排名', href: '/supply-chain/decision' },
			{ label: '处置与沙箱', description: '方案确认、执行资格与演练', href: '/supply-chain/disposal' },
			{ label: '任务历史', description: '报告、事件与执行轨迹回溯', href: '/supply-chain/history' },
		],
	},
	{
		title: '知识与问答',
		description: '让智能体理解业务口径与数据含义',
		features: [
			{ label: '数据问答', description: '基于真实数据的自然语言查询', href: '/chat' },
			{ label: '智能体知识', description: '文档、问答与向量化状态', href: '/knowledge/agents' },
			{ label: '业务术语', description: '业务名词、口径与同义词', href: '/knowledge/business' },
			{ label: '语义模型', description: '表字段的业务名称与说明', href: '/knowledge/semantic-models' },
		],
	},
	{
		title: '智能体与配置',
		description: '管理智能体、模型、数据源与提示词',
		features: [
			{ label: '智能体管理', description: '状态、API Key 与预设问题', href: '/system/agents' },
			{ label: '新建智能体', description: '配置名称、分类与系统提示词', href: '/agent/new' },
			{ label: '模型配置', description: '对话与向量模型连接', href: '/system/model-config' },
			{ label: '数据源管理', description: '连接、选表与 Schema 初始化', href: '/system/data-sources' },
			{ label: '提示词配置', description: '按类型与智能体维护提示词', href: '/prompt-config' },
		],
	},
];

export const HOME_DESIGN_INTENT = [
	'从自然语言业务问题进入供应链分析，而不是停留在静态报表。',
	'由 Java 后端执行确定性业务逻辑，AI 只负责解释与叙述。',
	'把风险依据、方案差异与处置前置条件明确展示出来。',
	'关键决策保留人工确认环节，确认前不进入执行。',
	'通过任务事件、报告与审计记录保留完整过程。',
	'使用沙箱演练验证处置步骤，不冒充真实生产执行。',
];

export const HOME_TECH_STACK = ['Java 17 · Spring Boot', 'Spring AI · Spring AI Alibaba', 'Next.js · React', 'TanStack Query'];
