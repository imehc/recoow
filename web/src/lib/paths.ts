import { asset, resolve } from '$app/paths';
import type { AssetPath, PathnameWithSearchOrHash } from '$app/types';

// Page and static-asset paths follow the deployment base; API paths remain /api.
export const appPath = (path: string) => resolve(path as PathnameWithSearchOrHash);
export const appAsset = (path: AssetPath) => asset(path);
