import { AlertTriangle, Loader2, RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';

export function LoadingState({ label = '加载中…', compact = false }: { label?: string; compact?: boolean }) {
	return (
		<div className={compact ? 'flex items-center gap-2 text-sm text-[#5a708c]' : 'flex min-h-[320px] flex-col items-center justify-center gap-3'}>
			<Loader2 className="h-6 w-6 animate-spin text-[#35629d]" />
			{!compact && <span className="text-sm text-[#5a708c]">{label}</span>}
		</div>
	);
}

export function ErrorState({
	title = '加载失败',
	message,
	onRetry,
}: {
	title?: string;
	message: string;
	onRetry?: () => void;
}) {
	return (
		<Card className="border-red-200 bg-red-50">
			<CardContent className="flex flex-wrap items-start justify-between gap-3 pt-5">
				<div className="flex min-w-0 items-start gap-2">
					<AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-[#b9575c]" />
					<div className="min-w-0">
						<p className="font-semibold text-[#8f3b45]">{title}</p>
						<p className="mt-0.5 break-words text-sm text-[#8f3b45]">{message}</p>
					</div>
				</div>
				{onRetry && (
					<Button variant="outline" size="sm" onClick={onRetry}>
						<RefreshCw className="mr-1.5 h-4 w-4" />
						重试
					</Button>
				)}
			</CardContent>
		</Card>
	);
}

export function EmptyState({
	title,
	description,
	action,
}: {
	title: string;
	description?: string;
	action?: React.ReactNode;
}) {
	return (
		<div className="flex flex-col items-center justify-center gap-2 py-12 text-center">
			<p className="text-base font-medium text-[#1c3048]">{title}</p>
			{description && <p className="max-w-xl text-sm text-[#5a708c]">{description}</p>}
			{action && <div className="mt-2">{action}</div>}
		</div>
	);
}
