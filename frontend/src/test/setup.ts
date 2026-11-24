import { setSocketFactory } from '../chat/chatConnection';
import { fakeSockets } from './fakeSocket';

// Every page renders the layout, which opens the chat socket once someone is
// signed in. No test may reach a real network socket, so the browser
// WebSocket is replaced here, before any test runs; tests that care about
// the socket drive the fakes this creates.
setSocketFactory((url) => fakeSockets.create(url));
