import { createBrowserRouter, Navigate } from 'react-router-dom';
import { AppShell } from '@/shell/AppShell';
import { DatabaseConnectionsPage } from '@/pages/DatabaseConnectionsPage';
import { DensitySamplePage } from '@/pages/DensitySamplePage';
import { MigrationRunPage } from '@/pages/MigrationRunPage';
import { MigrationTasksPage } from '@/pages/MigrationTasksPage';
import { MigrationWizardStagePage } from '@/pages/MigrationWizardStagePage';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { SettingsPage } from '@/pages/SettingsPage';
import { TableMigrationUnitPage } from '@/pages/TableMigrationUnitPage';
import { paths, routePatterns } from './paths';

export const routes = [
  {
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to={paths.migrationTasks} replace /> },
      { path: routePatterns.migrationTasks, element: <MigrationTasksPage /> },
      { path: routePatterns.wizardStage, element: <MigrationWizardStagePage /> },
      { path: routePatterns.migrationRun, element: <MigrationRunPage /> },
      { path: routePatterns.tableMigrationUnit, element: <TableMigrationUnitPage /> },
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
