import { createBrowserRouter, type RouteObject } from 'react-router';
import { GuestOnly, RequireAdmin, RequireSession, RequireTier } from './auth/guards';
import { AppLayout } from './layouts/AppLayout';
import { CheckInPage } from './pages/CheckInPage';
import { ConversationPage } from './pages/ConversationPage';
import { ConversationsPage } from './pages/ConversationsPage';
import { AdminUserLookupPage } from './pages/AdminUserLookupPage';
import { AdminVerificationQueuePage } from './pages/AdminVerificationQueuePage';
import { BlockedPage } from './pages/BlockedPage';
import { EmergencyContactPage } from './pages/EmergencyContactPage';
import { HomePage } from './pages/HomePage';
import { HostedTripsPage } from './pages/HostedTripsPage';
import { JoinRequestsPage } from './pages/JoinRequestsPage';
import { LedgerHistoryPage } from './pages/LedgerHistoryPage';
import { LiveTripPage } from './pages/LiveTripPage';
import { LoginPage } from './pages/LoginPage';
import { MatchPage } from './pages/MatchPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { ReviewsPage } from './pages/ReviewsPage';
import { RouteErrorPage } from './pages/RouteErrorPage';
import { SecondFactorPage } from './pages/SecondFactorPage';
import { SecurityPage } from './pages/SecurityPage';
import { SessionsPage } from './pages/SessionsPage';
import { SignupPage } from './pages/SignupPage';
import { TripBrowsePage } from './pages/TripBrowsePage';
import { TripCreatePage } from './pages/TripCreatePage';
import { TripDetailPage } from './pages/TripDetailPage';
import { TripLedgerPage } from './pages/TripLedgerPage';
import { TripReviewsPage } from './pages/TripReviewsPage';
import { TrustScorePage } from './pages/TrustScorePage';
import { UpiIdPage } from './pages/UpiIdPage';
import { VerificationPage } from './pages/VerificationPage';
import { VibeProfilePage } from './pages/VibeProfilePage';
import { VibeQuizPage } from './pages/VibeQuizPage';
import { VerifyEmailPage } from './pages/VerifyEmailPage';
import { VouchPage } from './pages/VouchPage';

/**
 * Public: home and the not-found page. Signed-out only: sign-up, email
 * verification and both login steps. Signed-in only: account screens,
 * trips and messages (no tier needed: verification is rewarded, never demanded).
 * Tier-gated: vouching needs the selfie tier. Admin: signed in, role ADMIN
 * and a second factor in this session.
 */
export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppLayout />,
    errorElement: <RouteErrorPage />,
    children: [
      { index: true, element: <HomePage /> },
      {
        element: <GuestOnly />,
        children: [
          { path: 'signup', element: <SignupPage /> },
          { path: 'verify-email', element: <VerifyEmailPage /> },
          { path: 'login', element: <LoginPage /> },
          { path: 'login/second-factor', element: <SecondFactorPage /> },
        ],
      },
      {
        element: <RequireSession />,
        children: [
          { path: 'account/security', element: <SecurityPage /> },
          { path: 'account/sessions', element: <SessionsPage /> },
          { path: 'account/verification', element: <VerificationPage /> },
          { path: 'vibe', element: <VibeProfilePage /> },
          { path: 'vibe/quiz', element: <VibeQuizPage /> },
          { path: 'trips', element: <TripBrowsePage /> },
          { path: 'trips/new', element: <TripCreatePage /> },
          { path: 'trips/mine', element: <HostedTripsPage /> },
          { path: 'trips/requests', element: <JoinRequestsPage /> },
          { path: 'trips/:tripId', element: <TripDetailPage /> },
          { path: 'trips/:tripId/settle-up', element: <TripLedgerPage /> },
          { path: 'trips/:tripId/settle-up/history', element: <LedgerHistoryPage /> },
          { path: 'trips/:tripId/live', element: <LiveTripPage /> },
          { path: 'trips/:tripId/reviews', element: <TripReviewsPage /> },
          { path: 'reviews', element: <ReviewsPage /> },
          { path: 'account/trust-score', element: <TrustScorePage /> },
          { path: 'account/blocked', element: <BlockedPage /> },
          { path: 'messages', element: <ConversationsPage /> },
          { path: 'messages/:conversationId', element: <ConversationPage /> },
          { path: 'matches/:matchId', element: <MatchPage /> },
          { path: 'matches/:matchId/check-in', element: <CheckInPage /> },
          { path: 'account/emergency-contact', element: <EmergencyContactPage /> },
          { path: 'account/upi-id', element: <UpiIdPage /> },
          {
            element: <RequireTier minimum="SELFIE_MATCH" />,
            children: [{ path: 'account/vouches', element: <VouchPage /> }],
          },
          {
            element: <RequireAdmin />,
            children: [
              { path: 'admin/users', element: <AdminUserLookupPage /> },
              { path: 'admin/verifications', element: <AdminVerificationQueuePage /> },
            ],
          },
        ],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];

export function createAppRouter() {
  return createBrowserRouter(routes);
}
