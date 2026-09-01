import {
  Content,
  Header,
  HeaderName,
  SideNav,
  SideNavItems,
  SideNavLink,
  SkipToContent,
  Theme,
} from '@carbon/react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { messages } from '@/messages';
import { paths, routePatterns } from '@/routes/paths';

/**
 * The persistent product shell (ADR-0007): task work, data-source management and system
 * settings are the three places work can live, and the shell says so on every screen.
 *
 * The theme is g10 for the whole product (ADR-0014). Inline g100 is reserved for the live
 * blocks inside run monitoring and is applied there, never at this level.
 */
/**
 * The three places work can live.
 *
 * Each item keeps its route *pattern* beside its builder: the pattern is what decides
 * whether the item is the active one, and the builder is what the link goes to. They are
 * no longer the same string, because a built URL carries the active `?scenario=` through
 * (see `src/routes/paths.ts`) and a query string is not part of what a navigation item
 * matches.
 */
const navItems = [
  {
    pattern: routePatterns.migrationTasks,
    to: paths.migrationTasks,
    label: messages.nav.migrationTasks,
  },
  {
    pattern: routePatterns.databaseConnections,
    to: paths.databaseConnections,
    label: messages.nav.databaseConnections,
  },
  { pattern: routePatterns.settings, to: paths.settings, label: messages.nav.settings },
] as const;

export function AppShell() {
  const { pathname } = useLocation();

  return (
    <Theme theme="g10">
      <Header aria-label={messages.product.name}>
        <SkipToContent />
        {/*
          A client-side link, not an `href`. A full page load would tear down the store and
          re-seed it from the fixture generator, so pressing the product name while a
          迁移运行 was being watched would silently discard it — and the built path is what
          carries the active `?scenario=` through (D25).
        */}
        <HeaderName<typeof Link> as={Link} to={paths.migrationTasks()} prefix="">
          {messages.product.name}
        </HeaderName>
      </Header>
      <SideNav
        isFixedNav
        expanded
        isChildOfHeader={false}
        aria-label={messages.nav.ariaLabel}
        addFocusListeners={false}
      >
        <SideNavItems>
          {navItems.map((item) => (
            <SideNavLink<typeof NavLink>
              key={item.pattern}
              as={NavLink}
              to={item.to()}
              isActive={pathname.startsWith(item.pattern)}
            >
              {item.label}
            </SideNavLink>
          ))}
        </SideNavItems>
      </SideNav>
      <Content id="main-content">
        <Outlet />
      </Content>
    </Theme>
  );
}
