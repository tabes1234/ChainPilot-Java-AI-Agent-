import { redirect } from 'next/navigation';

/** Legacy /agent/[id] entry kept as a redirect, mirroring the Nuxt app's compatibility stub. */
export default function LegacyAgentDetail() {
	redirect('/system/agents');
}
