import type { DatabaseConnection } from '@/contract';
import type { ControllableClock } from '../clock';
import type { SeedPlan } from '../scenarios';

/**
 * Seeded database connections.
 *
 * Deterministic by construction: fixed identifiers, fixed ordering, and timestamps
 * expressed as offsets from the scenario clock rather than from wall-clock time, so the
 * same scenario renders the same screen twice. The set deliberately covers all three
 * connection-check outcomes, because a list in which every row is healthy proves nothing
 * about how the failed and never-checked rows read.
 */
export function seedDatabaseConnections(
  plan: SeedPlan,
  clock: ControllableClock,
): DatabaseConnection[] {
  if (plan.databaseConnections === 'none') {
    return [];
  }

  const now = clock.now();
  const minutes = (count: number) => new Date(now - count * 60_000).toISOString();

  const connections: DatabaseConnection[] = [
    {
      id: 'conn-mysql-orders',
      name: '订单库（生产）',
      role: 'SOURCE',
      dialect: 'MYSQL_8_0',
      host: 'mysql-orders.prod.internal',
      port: 3306,
      database: 'orders',
      // A production MySQL server holds more than one database, and which one is being
      // migrated is the operator's choice rather than the endpoint's default.
      databases: ['orders', 'orders_archive', 'orders_reporting'],
      tls: 'SERVER_AUTHENTICATED',
      currentCredentialVersion: {
        id: 'cred-mysql-orders-3',
        connectionId: 'conn-mysql-orders',
        version: 3,
        username: 'dbx_reader',
        createdAt: minutes(2880),
        destroyedAt: null,
      },
      credentialVersionCount: 3,
      latestCheck: {
        outcome: 'SUCCEEDED',
        checkedAt: minutes(18),
        credentialVersionId: 'cred-mysql-orders-3',
        serverVersion: 'MySQL 8.0.36',
        failureReason: null,
      },
      archived: false,
      createdAt: minutes(43200),
      updatedAt: minutes(18),
    },
    {
      id: 'conn-pg-analytics',
      name: '分析库（生产）',
      role: 'TARGET',
      dialect: 'POSTGRESQL_15',
      host: 'pg-analytics.prod.internal',
      port: 5432,
      database: 'analytics',
      databases: ['analytics'],
      tls: 'MUTUAL',
      currentCredentialVersion: {
        id: 'cred-pg-analytics-2',
        connectionId: 'conn-pg-analytics',
        version: 2,
        username: 'dbx_owner',
        createdAt: minutes(10080),
        destroyedAt: null,
      },
      credentialVersionCount: 2,
      latestCheck: {
        outcome: 'SUCCEEDED',
        checkedAt: minutes(35),
        credentialVersionId: 'cred-pg-analytics-2',
        serverVersion: 'PostgreSQL 15.6',
        failureReason: null,
      },
      archived: false,
      createdAt: minutes(40320),
      updatedAt: minutes(35),
    },
    {
      id: 'conn-pg-staging',
      name: '分析库（预发）',
      role: 'TARGET',
      dialect: 'POSTGRESQL_15',
      host: 'pg-analytics.staging.internal',
      port: 5432,
      database: 'analytics',
      databases: ['analytics'],
      tls: 'SERVER_AUTHENTICATED',
      currentCredentialVersion: {
        id: 'cred-pg-staging-1',
        connectionId: 'conn-pg-staging',
        version: 1,
        username: 'dbx_owner',
        createdAt: minutes(20160),
        destroyedAt: null,
      },
      credentialVersionCount: 1,
      latestCheck: {
        outcome: 'FAILED',
        checkedAt: minutes(120),
        credentialVersionId: 'cred-pg-staging-1',
        serverVersion: null,
        failureReason: 'AUTHENTICATION_FAILED',
      },
      archived: false,
      createdAt: minutes(20160),
      updatedAt: minutes(120),
    },
    {
      id: 'conn-mysql-billing',
      name: '计费库（生产）',
      role: 'SOURCE',
      dialect: 'MYSQL_8_0',
      host: 'mysql-billing.prod.internal',
      port: 3306,
      database: 'billing',
      databases: ['billing', 'billing_archive'],
      tls: 'SERVER_AUTHENTICATED',
      currentCredentialVersion: {
        id: 'cred-mysql-billing-1',
        connectionId: 'conn-mysql-billing',
        version: 1,
        username: 'dbx_reader',
        createdAt: minutes(45),
        destroyedAt: null,
      },
      credentialVersionCount: 1,
      latestCheck: {
        outcome: 'NOT_RUN',
        checkedAt: null,
        credentialVersionId: null,
        serverVersion: null,
        failureReason: null,
      },
      archived: false,
      createdAt: minutes(45),
      updatedAt: minutes(45),
    },
  ];

  if (plan.databaseConnections === 'unchecked') {
    return connections.map((connection) => ({
      ...connection,
      latestCheck: {
        outcome: 'NOT_RUN',
        checkedAt: null,
        credentialVersionId: null,
        serverVersion: null,
        failureReason: null,
      },
    }));
  }

  return connections;
}

/**
 * Which seeded endpoints the mock can actually reach. A connection check is only worth
 * having in the product if it can also come back `FAILED`, so one seeded endpoint always
 * refuses the credential.
 */
export const unreachableConnectionIds: readonly string[] = ['conn-pg-staging'];
