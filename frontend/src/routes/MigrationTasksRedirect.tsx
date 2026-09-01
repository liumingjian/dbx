import { Navigate } from 'react-router-dom';
import { paths } from './paths';

/**
 * `/` sends the operator to 迁移任务.
 *
 * A component rather than an element built in the route table, because the redirect has to
 * carry whatever `?scenario=` the operator arrived with (lead decision D21) and a URL built
 * when the route module was evaluated could not: it would freeze whatever the address bar
 * happened to say at start-up.
 */
export function MigrationTasksRedirect() {
  return <Navigate to={paths.migrationTasks()} replace />;
}
