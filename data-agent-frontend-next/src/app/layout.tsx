import type { Metadata } from 'next';
import { Inter } from 'next/font/google';
import './globals.css';
import { AppShell } from '@/components/AppShell';
import { TooltipProvider } from '@/components/ui/tooltip';
import { Toaster } from '@/components/ui/sonner';
import { QueryProvider } from '@/lib/query/client';

const inter = Inter({ subsets: ['latin'] });

export const metadata: Metadata = {
	title: 'ChainPilot - 供应链智能决策工作台',
	description: '连接订单、库存与供应商数据，分析风险、比较方案，让每一步决策都有据可依。',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
	return (
		<html lang="zh-CN" className={`${inter.className} h-full`}>
			<body className="min-h-full antialiased">
				<QueryProvider>
					<TooltipProvider>
						<AppShell>{children}</AppShell>
						<Toaster richColors closeButton position="top-center" />
					</TooltipProvider>
				</QueryProvider>
			</body>
		</html>
	);
}
