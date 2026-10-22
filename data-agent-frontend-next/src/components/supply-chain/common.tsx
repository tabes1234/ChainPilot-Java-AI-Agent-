'use client';

import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';
import type { RiskLevel } from '@/lib/api/supplyChain';

export const RISK_LABELS: Record<RiskLevel, string> = { LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险' };
export const RISK_CLASSES: Record<RiskLevel, string> = {
	LOW: 'border-green-200 bg-green-50 text-green-700',
	MEDIUM: 'border-amber-200 bg-amber-50 text-amber-700',
	HIGH: 'border-red-200 bg-red-50 text-red-700',
};

export const TASK_STATUS_LABELS: Record<string, string> = {
	ACCEPTED: '已接收',
	RUNNING: '执行中',
	COMPLETED: '已完成',
	FAILED: '失败',
	CANCELLED: '已取消',
};

export function RiskBadge({ level }: { level?: RiskLevel }) {
	if (!level) return null;
	return <Badge variant="outline" className={RISK_CLASSES[level]}>{RISK_LABELS[level]}</Badge>;
}

export function StatusBadge({ status }: { status?: string }) {
	if (!status) return null;
	const className =
		status === 'COMPLETED' || status === 'SUCCEEDED' || status === 'CONFIRMED' || status === 'SELECTED' || status === 'PASSED'
			? 'border-green-200 bg-green-50 text-green-700'
			: status === 'FAILED' || status === 'REJECTED' || status === 'EXPIRED'
				? 'border-red-200 bg-red-50 text-red-700'
				: status === 'CANCELLED' || status === 'UNDETERMINED'
				? 'border-amber-200 bg-amber-50 text-amber-700'
				: status === 'RUNNING' || status === 'EXECUTING' || status === 'PROCESSING'
					? 'border-blue-200 bg-blue-50 text-blue-700'
					: 'border-gray-200 bg-gray-50 text-gray-600';
	return <Badge variant="outline" className={className}>{status}</Badge>;
}

export function Section({
	title,
	description,
	actions,
	children,
	className,
}: {
	title: string;
	description?: string;
	actions?: React.ReactNode;
	children: React.ReactNode;
	className?: string;
}) {
	return (
		<section className={cn('border-t border-[var(--cp-line)] pt-5', className)}>
			<div className="mb-3 flex flex-wrap items-start justify-between gap-2">
				<div className="min-w-0">
					<h2 className="text-[17px] font-semibold tracking-[-0.01em] text-[#233448]">{title}</h2>
					{description && <p className="mt-0.5 text-xs text-[#6a7d91]">{description}</p>}
				</div>
				{actions}
			</div>
			<div>{children}</div>
		</section>
	);
}

export function Field({ label, value, clamp }: { label: string; value?: React.ReactNode; clamp?: boolean }) {
	return (
		<div className="min-w-0">
			<div className="text-xs text-[#5a708c]">{label}</div>
			<div className={cn('mt-0.5 text-sm text-[#1c3048]', clamp && 'line-clamp-3')}>{value ?? '—'}</div>
		</div>
	);
}

export function BulletList({ items, empty = '暂无内容' }: { items?: (string | null | undefined)[]; empty?: string }) {
	const list = (items ?? []).filter((item): item is string => Boolean(item && item.trim()));
	if (list.length === 0) return <p className="text-sm text-[#5a708c]">{empty}</p>;
	return (
		<ul className="list-disc space-y-1 pl-5 text-sm text-[#33506b]">
			{list.map((item, index) => <li key={`${index}-${item.slice(0, 16)}`} className="[overflow-wrap:anywhere]">{item}</li>)}
		</ul>
	);
}

export function Metric({ label, value, hint }: { label: string; value: React.ReactNode; hint?: string }) {
	return (
		<div className="rounded-2xl bg-white/45 px-3.5 py-2.5 ring-1 ring-white/60">
			<div className="text-[12.5px] font-medium tracking-wide text-[#7a8da1]">{label}</div>
			<div className="mt-0.5 text-[19px] font-semibold leading-tight text-[#233448]">{value}</div>
			{hint && <div className="text-[12px] text-[#7a8da1]">{hint}</div>}
		</div>
	);
}

export function formatTime(value?: string) {
	if (!value) return '—';
	try {
		return new Date(value).toLocaleString('zh-CN', { hour12: false });
	} catch {
		return value;
	}
}

export function signed(value?: number | null) {
	if (value === undefined || value === null) return '—';
	return value > 0 ? `+${value}` : String(value);
}
