'use client';

import { useSyncExternalStore } from 'react';

const URL_CHANGE_EVENT = 'chainpilot:urlchange';

function emitUrlChange() {
	window.dispatchEvent(new Event(URL_CHANGE_EVENT));
}

let historyPatched = false;

/** Notify React when the App Router pushes/replaces the URL without a popstate event. */
function ensureHistoryPatched() {
	if (historyPatched || typeof window === 'undefined') return;
	historyPatched = true;
	const push = window.history.pushState.bind(window.history);
	const replace = window.history.replaceState.bind(window.history);
	window.history.pushState = (...args: Parameters<History['pushState']>) => {
		const result = push(...args);
		emitUrlChange();
		return result;
	};
	window.history.replaceState = (...args: Parameters<History['replaceState']>) => {
		const result = replace(...args);
		emitUrlChange();
		return result;
	};
}

function subscribe(onChange: () => void) {
	ensureHistoryPatched();
	window.addEventListener('popstate', onChange);
	window.addEventListener(URL_CHANGE_EVENT, onChange);
	return () => {
		window.removeEventListener('popstate', onChange);
		window.removeEventListener(URL_CHANGE_EVENT, onChange);
	};
}

function getSearch() {
	return window.location.search;
}

function getServerSearch() {
	return '';
}

/**
 * Reads a query parameter without `useSearchParams`, so statically prerendered pages
 * never suspend on a cold load. The server snapshot is empty; the client re-renders
 * after hydration or a client-side navigation when the URL carries the parameter.
 */
export function parseUrlParam(search: string, name: string): string | null {
	return new URLSearchParams(search).get(name);
}

export function useUrlParam(name: string): string | null {
	const search = useSyncExternalStore(subscribe, getSearch, getServerSearch);
	return parseUrlParam(search, name);
}
