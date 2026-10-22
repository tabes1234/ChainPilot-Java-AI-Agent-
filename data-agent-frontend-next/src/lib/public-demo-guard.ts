/**
 * Guard for public demo deployments. Only active when CHAINPILOT_PUBLIC_DEMO=true
 * (set at build time); local and internal environments are unaffected.
 */

const BLOCKED_API_PREFIXES = [
	'/api/model-config',
	'/api/prompt-config',
	'/api/datasource',
	'/api/agent-knowledge',
	'/api/business-knowledge',
	'/api/semantic-model',
	'/api/agent/',
	'/api/upload',
];

const BLOCKED_PAGE_PREFIXES = ['/system/model-config', '/system/agents', '/system/data-sources', '/prompt-config'];

export const PUBLIC_DEMO_BLOCK_MESSAGE = '该功能在公网演示环境中不可用。';

export function isPublicDemoBlockedApi(pathname: string): boolean {
	if (pathname === '/api/agent') return true;
	return BLOCKED_API_PREFIXES.some((prefix) => pathname === prefix || pathname.startsWith(prefix + '/') || (prefix.endsWith('/') && pathname.startsWith(prefix)));
}

export function isPublicDemoBlockedPage(pathname: string): boolean {
	return BLOCKED_PAGE_PREFIXES.some((prefix) => pathname === prefix || pathname.startsWith(prefix + '/'));
}
