import { useQuery } from '@tanstack/react-query';
import { api } from '../../lib/api';

/** The profile sections and their fields, from the API (they rarely change). */
export function useSectionSpecs() {
  return useQuery({ queryKey: ['profile', 'sections'], queryFn: () => api.profiles.sections(), staleTime: Infinity });
}
