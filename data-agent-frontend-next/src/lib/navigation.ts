import {
	Activity,
	BookOpen,
	FlaskConical,
	Brain,
	Database,
	Cpu,
	History,
	Home,
	Link2,
	MessageCircleQuestion,
	MessageSquareText,
	Microchip,
	PlayCircle,
	PlusSquare,
	Radar,
	ShieldCheck,
	SlidersHorizontal,
	WandSparkles,
} from 'lucide-react';

export type NavItem = {
	label: string;
	href: string;
	icon: React.ElementType;
	group?: string;
};

export const NAV_ITEMS: NavItem[] = [
	{ label: '首页', href: '/', icon: Home },
	{ label: '项目演示', href: '/demo', icon: PlayCircle },
	{ label: '供应链分析', href: '/supply-chain', icon: Radar, group: '工作台' },
	{ label: '数据问答', href: '/chat', icon: MessageSquareText, group: '工作台' },
	{ label: '任务记录', href: '/supply-chain/history', icon: History, group: '工作台' },
	{ label: '自然语言决策', href: '/supply-chain/decision', icon: MessageCircleQuestion, group: '风险决策' },
	{ label: '处置与沙箱', href: '/supply-chain/disposal', icon: ShieldCheck, group: '风险决策' },
	{ label: '情景模拟', href: '/supply-chain/simulation', icon: SlidersHorizontal, group: '风险决策' },
	{ label: '工作流评测演练', href: '/supply-chain/workflow-evaluation', icon: FlaskConical, group: '风险决策' },
	{ label: '业务术语', href: '/knowledge/business', icon: BookOpen, group: '知识与智能体' },
	{ label: '智能体知识', href: '/knowledge/agents', icon: Brain, group: '知识与智能体' },
	{ label: '语义模型', href: '/knowledge/semantic-models', icon: Link2, group: '知识与智能体' },
	{ label: '智能体管理', href: '/system/agents', icon: Microchip, group: '知识与智能体' },
	{ label: '新建智能体', href: '/agent/new', icon: PlusSquare, group: '知识与智能体' },
	{ label: 'Agent 运行观测', href: '/agent-observability', icon: Activity, group: 'Agent 工程' },
	{ label: 'Agent 评测中心', href: '/agent-evaluation', icon: FlaskConical, group: 'Agent 工程' },
	{ label: '模型配置', href: '/system/model-config', icon: Cpu, group: '系统设置' },
	{ label: '提示词配置', href: '/prompt-config', icon: WandSparkles, group: '系统设置' },
	{ label: '数据源管理', href: '/system/data-sources', icon: Database, group: '系统设置' },
];

export const NAV_GROUPS = ['工作台', '风险决策', '知识与智能体', 'Agent 工程', '系统设置'];

export function isNavItemActive(pathname: string | null, item: NavItem): boolean {
	if (!pathname) return false;
	if (item.href === '/') return pathname === '/';
	if (item.href === '/supply-chain') {
		// The analysis workspace must not light up on /supply-chain/decision|disposal|history.
		return pathname === '/supply-chain';
	}
	return pathname === item.href || pathname.startsWith(`${item.href}/`);
}

export function currentNavItem(pathname: string | null): NavItem | undefined {
	if (!pathname) return undefined;
	return [...NAV_ITEMS]
		.sort((a, b) => b.href.length - a.href.length)
		.find((item) => isNavItemActive(pathname, item));
}
