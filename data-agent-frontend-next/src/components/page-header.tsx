import { cn } from '@/lib/utils';

export function PageHeader({
	title,
	description,
	breadcrumb,
	actions,
	className,
}: {
	title: string;
	description?: string;
	breadcrumb?: string;
	actions?: React.ReactNode;
	className?: string;
}) {
	return (
		<header className={cn('flex flex-col gap-4 pb-1 md:flex-row md:items-end md:justify-between', className)}>
			<div className="min-w-0">
				{breadcrumb && <p className="cp-eyebrow mb-2">{breadcrumb}</p>}
				<h1 className="text-[clamp(25px,2.5vw,34px)] font-semibold leading-tight tracking-[-0.02em] text-[#1f2a37]">{title}</h1>
				{description && <p className="mt-2 max-w-3xl text-[14.5px] leading-relaxed text-[#5f7389]">{description}</p>}
			</div>
			{actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
		</header>
	);
}
