/**
 * ChainPilot Design Tokens
 * Unified color, spacing, typography and animation system.
 */

export const colors = {
	// Brand
	primary: '#35629d',
	primaryDeep: '#264c79',
	primarySoft: '#e8f0f8',
	focus: 'rgba(53, 98, 157, 0.72)',

	// Surfaces
	bg: '#f7fbff',
	surface: '#ffffff',
	surfaceMuted: '#f3f8fc',

	// Text
	ink: '#263b57',
	inkStrong: '#1c3048',
	inkSecondary: '#405a78',
	muted: '#5a708c',
	subtle: '#60758f',

	// Lines
	line: '#dce8f7',
	lineSoft: '#ecf2f8',

	// Risk semantics (must not be confused with agent roles)
	riskHigh: '#b9575c',
	riskMedium: '#b38340',
	riskLow: '#428766',

	// Agent roles
	rolePlanning: '#4b7fc8',
	roleData: '#3e9487',
	roleRisk: '#b38340',
	roleSimulation: '#796bbb',
	roleConfirmation: '#bd7361',
	roleExecution: '#428766',
	roleSystem: '#6f8197',
	roleFailure: '#b9575c',
};

export const spacing = {
	1: '4px',
	2: '8px',
	3: '12px',
	4: '16px',
	5: '24px',
	6: '32px',
};

export const radii = {
	xs: '8px',
	sm: '11px',
	md: '14px',
	lg: '18px',
	pill: '999px',
};

export const shadows = {
	soft: '0 10px 28px rgba(31, 58, 88, 0.065), 0 1px 2px rgba(31, 58, 88, 0.035)',
	raised: '0 18px 42px rgba(31, 58, 88, 0.09), 0 2px 5px rgba(31, 58, 88, 0.04)',
};

export const durations = {
	fast: '150ms',
	base: '240ms',
	slow: '480ms',
};

export const easings = {
	out: 'cubic-bezier(0.22, 0.61, 0.36, 1)',
	soft: 'cubic-bezier(0.4, 0, 0.2, 1)',
};

export const layout = {
	maxContentWidth: '1600px',
	sidebarWidth: '280px',
	sidebarCollapsedWidth: '64px',
	headerHeight: '56px',
};

export const typography = {
	pageTitle: 'clamp(28px, 2.7vw, 38px)',
	pageDescription: '16px',
	sectionTitle: 'clamp(20px, 2vw, 24px)',
	body: '15.5px',
	table: '14.5px',
	helper: '13.5px',
	button: '15px',
};
