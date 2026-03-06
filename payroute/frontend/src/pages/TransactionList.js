import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { paymentsApi } from '../services/api';
import { format } from 'date-fns';

const STATUS_OPTIONS = ['all', 'initiated', 'processing', 'completed', 'failed', 'reversed'];

function StatusBadge({ status }) {
  return <span className={`badge badge-${status}`}>{status}</span>;
}

function formatAmount(amount, currency) {
  if (amount == null) return '—';
  return new Intl.NumberFormat('en-NG', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    .format(amount) + ' ' + currency;
}

export default function TransactionList() {
  const navigate = useNavigate();
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [filters, setFilters] = useState({ status: 'all', from: '', to: '' });
  const [page, setPage] = useState(0);

  const fetchData = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const params = {
        page,
        size: 20,
        ...(filters.status !== 'all' && { status: filters.status }),
        ...(filters.from && { from: new Date(filters.from).toISOString() }),
        ...(filters.to && { to: new Date(filters.to).toISOString() }),
      };
      const res = await paymentsApi.list(params);
      setData(res);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [page, filters]);

  useEffect(() => { fetchData(); }, [fetchData]);

  const handleFilterChange = (key, value) => {
    setFilters((f) => ({ ...f, [key]: value }));
    setPage(0);
  };

  return (
    <div>
      <div className="page-header">
        <h1>Transactions</h1>
        <p>All cross-border payment transactions</p>
      </div>

      <div className="filters">
        <select
          value={filters.status}
          onChange={(e) => handleFilterChange('status', e.target.value)}
        >
          {STATUS_OPTIONS.map((s) => (
            <option key={s} value={s}>{s === 'all' ? 'All Statuses' : s.charAt(0).toUpperCase() + s.slice(1)}</option>
          ))}
        </select>
        <input
          type="datetime-local"
          value={filters.from}
          onChange={(e) => handleFilterChange('from', e.target.value)}
          placeholder="From"
        />
        <input
          type="datetime-local"
          value={filters.to}
          onChange={(e) => handleFilterChange('to', e.target.value)}
          placeholder="To"
        />
        <button className="btn btn-secondary" onClick={() => { setFilters({ status: 'all', from: '', to: '' }); setPage(0); }}>
          Clear
        </button>
      </div>

      {error && <div className="error-box">{error}</div>}

      <div className="card" style={{ padding: 0 }}>
        {loading ? (
          <div className="loading">Loading transactions...</div>
        ) : (
          <>
            <div className="table-wrapper">
              <table>
                <thead>
                  <tr>
                    <th>Reference</th>
                    <th>Sender</th>
                    <th>Recipient</th>
                    <th>Source Amount</th>
                    <th>Dest Amount</th>
                    <th>FX Rate</th>
                    <th>Status</th>
                    <th>Created</th>
                  </tr>
                </thead>
                <tbody>
                  {data?.data?.length === 0 ? (
                    <tr><td colSpan="8" style={{ textAlign: 'center', padding: '40px', color: '#9ca3af' }}>No transactions found</td></tr>
                  ) : data?.data?.map((tx) => (
                    <tr
                      key={tx.id}
                      className="clickable"
                      onClick={() => navigate(`/payments/${tx.id}`)}
                    >
                      <td>
                        <code style={{ fontSize: 12, background: '#f3f4f6', padding: '2px 6px', borderRadius: 4 }}>
                          {tx.reference}
                        </code>
                      </td>
                      <td>{tx.senderName}</td>
                      <td>
                        <div>{tx.recipientName}</div>
                        <div style={{ fontSize: 11, color: '#9ca3af' }}>{tx.recipientCountry}</div>
                      </td>
                      <td>
                        <div className="amount-main">{formatAmount(tx.sourceAmount, tx.sourceCurrency)}</div>
                        {tx.feeAmount > 0 && (
                          <div className="amount-sub">Fee: {formatAmount(tx.feeAmount, tx.sourceCurrency)}</div>
                        )}
                      </td>
                      <td className="amount-main">{formatAmount(tx.destAmount, tx.destCurrency)}</td>
                      <td style={{ fontSize: 13 }}>{tx.fxRate?.toFixed(6)}</td>
                      <td><StatusBadge status={tx.status} /></td>
                      <td style={{ fontSize: 12, color: '#6b7280' }}>
                        {tx.createdAt ? format(new Date(tx.createdAt), 'MMM d, HH:mm') : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {data && (
              <div className="pagination" style={{ padding: '12px 16px' }}>
                <button onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page === 0}>
                  ← Prev
                </button>
                <span className="page-info">
                  Page {page + 1} of {data.totalPages || 1} · {data.totalElements} total
                </span>
                <button
                  onClick={() => setPage((p) => p + 1)}
                  disabled={page + 1 >= (data.totalPages || 1)}
                >
                  Next →
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
