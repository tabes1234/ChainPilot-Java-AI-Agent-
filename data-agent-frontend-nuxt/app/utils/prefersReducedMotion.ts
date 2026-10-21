export function prefersReducedMotion(): boolean {
	if (!import.meta.client) return false;
	return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
}

export function scrollBehavior(): ScrollBehavior {
	return prefersReducedMotion() ? 'auto' : 'smooth';
}