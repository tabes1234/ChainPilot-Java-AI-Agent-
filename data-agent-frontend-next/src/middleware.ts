import { NextResponse, type NextRequest } from 'next/server';
import { demoClosedFeature, demoNoticeHref } from '@/lib/demo-mode';
import {
	PUBLIC_DEMO_BLOCK_MESSAGE,
	isPublicDemoBlockedApi,
	isPublicDemoBlockedPage,
} from '@/lib/public-demo-guard';

// Direct-URL interception for competition demo mode: runs on the server for both fresh
// visits and client-side navigations, so a closed route never renders or fetches data.
// CHAINPILOT_PUBLIC_DEMO additionally hides configuration surfaces in public deployments.
const PUBLIC_DEMO = process.env.CHAINPILOT_PUBLIC_DEMO === 'true';

export function middleware(request: NextRequest) {
	const pathname = request.nextUrl.pathname;

	if (PUBLIC_DEMO && isPublicDemoBlockedApi(pathname)) {
		return NextResponse.json({ success: false, message: PUBLIC_DEMO_BLOCK_MESSAGE }, { status: 403 });
	}

	if (PUBLIC_DEMO && isPublicDemoBlockedPage(pathname)) {
		const url = request.nextUrl.clone();
		url.pathname = demoNoticeHref('系统配置').split('?')[0];
		url.search = `feature=${encodeURIComponent('系统配置')}`;
		return NextResponse.redirect(url);
	}

	const feature = demoClosedFeature(pathname);
	if (!feature) return NextResponse.next();
	const url = request.nextUrl.clone();
	url.pathname = demoNoticeHref(feature).split('?')[0];
	url.search = `feature=${encodeURIComponent(feature)}`;
	return NextResponse.redirect(url);
}

export const config = {
	matcher: ['/((?!_next/static|_next/image|favicon.ico|feature-preparing).*)'],
};
