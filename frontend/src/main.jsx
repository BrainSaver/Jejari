import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import App from './App.jsx';
import './styles/tokens.css';
import './styles/admin.css';
import './styles/user.css';
import './styles/motion.css';
import { installRipple } from './lib/ripple.js';
import { startRecorder } from './replay/recorder.js';

installRipple();
startRecorder(); // ADM-11 회원 화면 녹화 (서버가 꺼 두면 아무것도 안 함)

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
