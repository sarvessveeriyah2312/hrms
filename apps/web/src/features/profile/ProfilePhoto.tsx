import { useEffect, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../lib/api';
import { Avatar } from '../../components/app/kit';

/** The person's photo (fetched with the session), or their initials when there's none. */
export function ProfilePhoto({ id, name, hasPhoto, size = 64 }: { id: string; name: string | null | undefined; hasPhoto: boolean; size?: number }) {
  const photo = useQuery({ queryKey: ['profile', 'photo', id], queryFn: () => api.profiles.photo(id), enabled: hasPhoto, staleTime: 5 * 60_000 });
  const url = useMemo(() => (hasPhoto && photo.data ? URL.createObjectURL(photo.data) : null), [hasPhoto, photo.data]);
  useEffect(() => () => {
    if (url) URL.revokeObjectURL(url);
  }, [url]);

  if (!url) return <Avatar name={name} size={size} />;
  return <img className="profile-photo" src={url} alt="" width={size} height={size} style={{ width: size, height: size }} />;
}
