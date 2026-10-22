'use client';

import { DEMO_MODE_ENABLED, demoNoticeHref } from '@/lib/demo-mode';

// Returns true when the click was intercepted (demo mode navigated to the unified notice
// page), so callers can bail out before any request or state change happens. Uses a hard
// navigation so it works in any component without a router context.
export function useDemoGuard(): (feature: string) => boolean {
	return (feature: string): boolean => {
		if (!DEMO_MODE_ENABLED) return false;
		if (typeof window !== 'undefined') window.location.assign(demoNoticeHref(feature));
		return true;
	};
}
