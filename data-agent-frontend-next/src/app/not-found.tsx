import Link from 'next/link';
import { Button } from '@/components/ui/button';

export default function NotFound() {
	return (
		<div className="mx-auto flex max-w-xl flex-col items-center gap-3 p-10 text-center">
			<p className="text-5xl font-black text-[#cddced]">404</p>
			<h1 className="text-xl font-bold text-[#1c3048]">页面不存在</h1>
			<p className="text-sm text-[#5a708c]">该地址没有对应的 ChainPilot 页面，可能已被移除或输入的链接有误。</p>
			<Link href="/" className="mt-2">
				<Button className="bg-[#35629d] hover:bg-[#264c79]">返回首页</Button>
			</Link>
		</div>
	);
}
