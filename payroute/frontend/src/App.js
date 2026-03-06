import React from 'react';
import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom';
import TransactionList from './pages/TransactionList';
import TransactionDetail from './pages/TransactionDetail';
import PaymentForm from './pages/PaymentForm';
import './App.css';

function App() {
  return (
    <BrowserRouter>
      <div className="app">
        <nav className="sidebar">
          <div className="logo">
            <span className="logo-icon">₦→</span>
            <span className="logo-text">PayRoute</span>
          </div>
          <NavLink to="/" end className={({ isActive }) => isActive ? 'nav-link active' : 'nav-link'}>
            📋 Transactions
          </NavLink>
          <NavLink to="/new" className={({ isActive }) => isActive ? 'nav-link active' : 'nav-link'}>
            ➕ New Payment
          </NavLink>
        </nav>

        <main className="main-content">
          <Routes>
            <Route path="/" element={<TransactionList />} />
            <Route path="/payments/:id" element={<TransactionDetail />} />
            <Route path="/new" element={<PaymentForm />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  );
}

export default App;
