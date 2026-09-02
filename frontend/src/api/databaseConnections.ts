import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  AddCredentialVersionRequest,
  DatabaseConnection,
  RegisterDatabaseConnectionRequest,
} from '@/contract';
import { getJson, postJson } from './http';
import { dbxQueryKey } from './queryKeys';

/**
 * Reading and maintaining database connections.
 *
 * Registration and credential maintenance live here and are used only by 数据源 — the
 * migration wizard never opens a hole for a credential to be typed into (`CONTEXT.md`,
 * `Data source management`).
 */

interface DatabaseConnectionListResponse {
  readonly items: readonly DatabaseConnection[];
}

export const databaseConnectionKeys = {
  all: () => dbxQueryKey('database-connections'),
};

export function useDatabaseConnections() {
  return useQuery({
    queryKey: databaseConnectionKeys.all(),
    queryFn: async () =>
      (await getJson<DatabaseConnectionListResponse>('/database-connections')).items,
  });
}

export function useRegisterDatabaseConnection() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: RegisterDatabaseConnectionRequest) =>
      postJson<DatabaseConnection>('/database-connections', request),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: databaseConnectionKeys.all() }),
  });
}

export function useAddCredentialVersion() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (variables: { id: string; request: AddCredentialVersionRequest }) =>
      postJson<DatabaseConnection>(
        `/database-connections/${encodeURIComponent(variables.id)}/credential-versions`,
        variables.request,
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: databaseConnectionKeys.all() }),
  });
}

export function useCheckDatabaseConnection() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      postJson<DatabaseConnection>(`/database-connections/${encodeURIComponent(id)}/checks`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: databaseConnectionKeys.all() }),
  });
}
