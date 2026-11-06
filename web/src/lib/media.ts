export function mediaReferences(
  snapshot?: Record<string, unknown> | null,
): { key: string; contentType?: string }[] {
  const media = snapshot?._media;
  if (!media || typeof media !== 'object') return [];
  return Object.values(media).flatMap((value) =>
    value && typeof value === 'object' && 'key' in value && typeof value.key === 'string'
      ? [
          {
            key: value.key,
            contentType:
              'contentType' in value && typeof value.contentType === 'string'
                ? value.contentType
                : undefined,
          },
        ]
      : [],
  );
}
export const mediaURL = (key: string) =>
  `/api/media/objects/${key.split('/').map(encodeURIComponent).join('/')}`;
