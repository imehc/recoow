import { asset, resolve } from '$app/paths';
import type { AssetPath } from '$app/types';

// 页面与静态资源路径跟随部署基路径，API 路径仍为 /api。
export const appPath = (path: string) => resolve('/') + path.replace(/^\//, '');
export const appAsset = (path: AssetPath) => asset(path);
