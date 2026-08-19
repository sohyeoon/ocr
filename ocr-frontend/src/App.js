import React from 'react';
import { BrowserRouter as Router, Routes, Route, Link } from 'react-router-dom';
import AdminPage from './pages/AdminPage';
import SearchPage from './pages/SearchPage';
import './App.css';

function App() {
  return (
    <Router>
      <div className="app">
        <header className="app-header">
          <div className="header-content">
            <h1 className="logo">📄 파일 내용 검색 시스템</h1>
            <nav className="nav-links">
              <Link to="/">검색</Link>
              <Link to="/admin">관리자</Link>
            </nav>
          </div>
        </header>
        <main className="app-main">
          <Routes>
            <Route path="/" element={<SearchPage />} />
            <Route path="/admin" element={<AdminPage />} />
          </Routes>
        </main>
      </div>
    </Router>
  );
}

export default App;