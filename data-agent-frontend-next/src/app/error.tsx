'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { AlertTriangle, Home, RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';

export default function GlobalError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
	useEffect(() => {
		// Keep the console entry for diagnosis; it contains no credentials.
		console.error('[ChainPilot] page error:', error.message);
	}, [error]);

	return (
		<div className="mx-auto flex max-w-2xl flex-col items-center gap-3 p-10 text-center">
			<AlertTriangle className="h-10 w-10 text-[#b38340]" />
			<h1 className="text-xl font-bold text-[#1c3048]">页面出现异常</h1>
			<p className="text-sm text-[#5a708c]">{error.message || '未知错误'}</p>
			<div className="mt-2 flex gap-2">
				<Button onClick={reset} className="bg-[#35629d] hover:bg-[#264c79]">
					<RefreshCw className="mr-2 h-4 w-4" />
					重试
				</Button>
				<Link href="/">
					<Button variant="outline">
						<Home className="mr-2 h-4 w-4" />
						返回首页
					</Button>
				</Link>
			</div>
		</div>
	);
}
