import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import App from './App.jsx';
import './styles/tokens.css';
import './styles/admin.css';
import './styles/user.css';
import './styles/motion.css';
import { installRipple } from './lib/ripple.js';

installRipple();

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
