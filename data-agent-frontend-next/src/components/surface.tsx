import { cn } from '@/lib/utils';

/** A quiet frosted panel. Used instead of stacking bordered cards. */
export function Surface({
	children,
	className,
	as: Tag = 'section',
}: {
	children: React.ReactNode;
	className?: string;
	as?: 'section' | 'div' | 'article';
}) {
	return <Tag className={cn('cp-surface p-4 sm:p-5', className)}>{children}</Tag>;
}

/** Filter/search row that sits directly on the page background. */
export function Toolbar({ children, className }: { children: React.ReactNode; className?: string }) {
	return <div className={cn('cp-toolbar', className)}>{children}</div>;
}

export function SectionHeading({
	title,
	description,
	actions,
	className,
}: {
	title: string;
	description?: string;
	actions?: React.ReactNode;
	className?: string;
}) {
	return (
		<div className={cn('mb-3 flex flex-wrap items-start justify-between gap-2', className)}>
			<div className="min-w-0">
				<h2 className="text-[15px] font-semibold tracking-[-0.01em] text-[#233448]">{title}</h2>
				{description && <p className="mt-0.5 text-xs text-[#6a7d91]">{description}</p>}
			</div>
			{actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
		</div>
	);
}

/** Small inline metric, used instead of large stat cards. */
export function InlineStat({ label, value }: { label: string; value: React.ReactNode }) {
	return (
		<div className="flex min-w-[96px] flex-col gap-0.5 px-1">
			<span className="text-[11px] font-medium tracking-wide text-[#7a8da1]">{label}</span>
			<span className="text-[17px] font-semibold leading-tight text-[#233448]">{value}</span>
		</div>
	);
}
