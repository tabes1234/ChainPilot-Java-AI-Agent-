import { redirect } from 'next/navigation';

export default function LegacyAgentIndex() {
	redirect('/system/agents');
}
