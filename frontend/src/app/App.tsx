import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router-dom';
import { router } from '@/routes/router';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Per ADR-0004 progress observations may be coalesced, so no view may assume a
      // smooth, monotonic advance. Refetching on window focus would hide that.
      refetchOnWindowFocus: false,
      retry: 1,
    },
  },
});

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} future={{ v7_startTransition: true }} />
    </QueryClientProvider>
  );
}
