'use client';

import { useId } from 'react';
import { Loader2, Send, Square } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { cn } from '@/lib/utils';

/**
 * Shared Gemini-style composer: wide, rounded, auto-growing, soft focus ring.
 * Used by the chat workspace; the brand home no longer contains an input.
 */
export function PromptComposer({
	value,
	onChange,
	onSubmit,
	running = false,
	busy = false,
	disabled = false,
	onStop,
	placeholder = '输入问题，Enter 发送，Shift + Enter 换行',
	hint,
	className,
}: {
	value: string;
	onChange: (value: string) => void;
	onSubmit: () => void;
	running?: boolean;
	busy?: boolean;
	disabled?: boolean;
	onStop?: () => void;
	placeholder?: string;
	hint?: string;
	className?: string;
}) {
	const id = useId();
	return (
		<div className={cn('cp-composer p-2.5', className)}>
			<label htmlFor={id} className="sr-only">输入问题</label>
			<Textarea
				id={id}
				rows={1}
				placeholder={placeholder}
				value={value}
				disabled={disabled || running}
				onChange={(event) => onChange(event.target.value)}
				onKeyDown={(event) => {
					if (event.key === 'Enter' && !event.shiftKey) {
						event.preventDefault();
						onSubmit();
					}
				}}
				className="max-h-48 min-h-[52px] resize-none border-0 bg-transparent px-3 py-2.5 text-[14.5px] leading-relaxed shadow-none focus-visible:border-0 focus-visible:shadow-none [field-sizing:content]"
			/>
			<div className="flex items-center justify-between gap-3 px-2 pb-0.5">
				<span className="text-[11px] text-[#8a9aac]">{hint ?? '回答来自后端真实执行流程 · 失败会明确提示'}</span>
				<div className="flex items-center gap-2">
					{running ? (
						<Button variant="outline" size="sm" className="rounded-full" onClick={onStop}>
							<Square className="mr-1.5 h-3.5 w-3.5" />停止
						</Button>
					) : (
						<Button
							size="icon"
							className="h-10 w-10 rounded-full bg-[#2b5583] text-white hover:bg-[#35629d]"
							disabled={busy || !value.trim()}
							onClick={onSubmit}
							aria-label="发送"
						>
							{busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
						</Button>
					)}
				</div>
			</div>
		</div>
	);
}
