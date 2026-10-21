/** Backend limit comes from data-agent-management `file.imageSize: 2097152` (2 MB). */
export const AVATAR_MAX_BYTES = 2 * 1024 * 1024;

export type AvatarValidation = { ok: true } | { ok: false; message: string };

export function validateAvatarFile(file: Pick<File, 'type' | 'size'>, maxBytes = AVATAR_MAX_BYTES): AvatarValidation {
	if (!file.type || !file.type.startsWith('image/')) {
		return { ok: false, message: '请选择图片文件（后端仅接受 image/* 类型）' };
	}
	if (file.size > maxBytes) {
		return { ok: false, message: `头像不能超过 ${Math.round(maxBytes / 1024 / 1024)}MB` };
	}
	return { ok: true };
}
