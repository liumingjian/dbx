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
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { messages } from '@/messages';
import { paths } from '@/routes/paths';

/**
 * The persistent product shell (ADR-0007): task work, data-source management and system
 * settings are the three places work can live, and the shell says so on every screen.
 *
 * The theme is g10 for the whole product (ADR-0014). Inline g100 is reserved for the live
 * blocks inside run monitoring and is applied there, never at this level.
 */
const navItems = [
  { to: paths.migrationTasks, label: messages.nav.migrationTasks },
  { to: paths.databaseConnections, label: messages.nav.databaseConnections },
  { to: paths.settings, label: messages.nav.settings },
] as const;

export function AppShell() {
  const { pathname } = useLocation();

  return (
    <Theme theme="g10">
      <Header aria-label={messages.product.name}>
        <SkipToContent />
        <HeaderName href={paths.migrationTasks} prefix="">
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
              key={item.to}
              as={NavLink}
              to={item.to}
              isActive={pathname.startsWith(item.to)}
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
