import { createBrowserRouter } from 'react-router-dom';
import { AppShell } from '@/shell/AppShell';
import { DatabaseConnectionsPage } from '@/pages/DatabaseConnectionsPage';
import { DensitySamplePage } from '@/pages/DensitySamplePage';
import { MigrationRunPage } from '@/pages/MigrationRunPage';
import { MigrationTaskRunsPage } from '@/pages/MigrationTaskRunsPage';
import { MigrationTasksPage } from '@/pages/MigrationTasksPage';
import { MigrationWizardStagePage } from '@/pages/MigrationWizardStagePage';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { SettingsPage } from '@/pages/SettingsPage';
import { TableMigrationUnitPage } from '@/pages/TableMigrationUnitPage';
import { MigrationTasksRedirect } from './MigrationTasksRedirect';
import { routePatterns } from './paths';

/** `tables/:unitId` — the run-relative half of the 表迁移单元 pattern. */
const tableMigrationUnitChildPath = routePatterns.tableMigrationUnit.slice(
  routePatterns.migrationRun.length + 1,
);

export const routes = [
  {
    element: <AppShell />,
    children: [
      { index: true, element: <MigrationTasksRedirect /> },
      { path: routePatterns.migrationTasks, element: <MigrationTasksPage /> },
      { path: routePatterns.wizardStage, element: <MigrationWizardStagePage /> },
      { path: routePatterns.migrationTaskRuns, element: <MigrationTaskRunsPage /> },
      /**
       * 单表证据 is a **child** of the run it belongs to (#39).
       *
       * The drawer is an overlay with its own URL, and nesting is what makes both halves
       * of that true at once: 运行监控 stays mounted and rendered underneath, so a deep
       * link and a reload restore the drawer *over the run* rather than on a page of its
       * own. The child path is derived from the same pattern `paths` builds from, so the
       * URL a link produces and the URL the router matches cannot drift apart.
       */
      {
        path: routePatterns.migrationRun,
        element: <MigrationRunPage />,
        children: [{ path: tableMigrationUnitChildPath, element: <TableMigrationUnitPage /> }],
      },
      { path: routePatterns.databaseConnections, element: <DatabaseConnectionsPage /> },
      { path: routePatterns.settings, element: <SettingsPage /> },
      { path: routePatterns.densitySample, element: <DensitySamplePage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];

/** Opt in early to the v7 behaviours so the upgrade is not a behavioural surprise. */
export const routerFutureFlags = {
  v7_startTransition: true,
  v7_relativeSplatPath: true,
  v7_fetcherPersist: true,
  v7_normalizeFormMethod: true,
  v7_partialHydration: true,
  v7_skipActionErrorRevalidation: true,
} as const;

export const router = createBrowserRouter(routes, { future: routerFutureFlags });
