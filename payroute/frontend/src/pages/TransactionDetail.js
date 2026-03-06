import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { paymentsApi } from '../services/api';
import { format } from 'date-fns';

function StatusBadge({ status }) {
  return <span className={`badge badge-${status}`}>{status}</span>;
}

function formatAmount(amount, currency) {
  if (amount == null) return '—';
  return new Intl.NumberFormat('en-NG', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    .format(amount) + ' ' + currency;
}

function DetailField({ label, value, children }) {
  return (
    <div className="detail-field">
      <div className="detail-label">{label}</div>
      <div className="detail-value">{children || value || '—'}</div>
    </div>
  );
}

export default function TransactionDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [tx, setTx] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    paymentsApi.get(id)
      .then(setTx)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, [id]);

  if (loading) return <div className="loading">Loading transaction...</div>;
  if (error) return <div className="error-box">{error}</div>;
  if (!tx) return null;

  return (
    <div>
      <div style={{ marginBottom: 20 }}>
        <button className="back-link" onClick={() => navigate('/')}>← Back to Transactions</button>
      </div>

      <div className="page-header">
        <h1>
          <code style={{ fontSize: 18 }}>{tx.reference}</code>{' '}
          <StatusBadge status={tx.status} />
        </h1>
        <p>Created {tx.createdAt ? format(new Date(tx.createdAt), 'PPpp') : '—'}</p>
      </div>

      {/* Summary */}
      <div className="card">
        <div className="card-title">Payment Summary</div>
        <div className="detail-grid">
          <div>
            <DetailField label="Sender" value={tx.senderName} />
            <DetailField label="Recipient" value={tx.recipientName} />
            <DetailField label="Recipient Country" value={tx.recipientCountry} />
            <DetailField label="Provider Reference" value={tx.providerReference} />
          </div>
          <div>
            <DetailField label="Source Amount">
              <span style={{ fontSize: 20, fontWeight: 700 }}>{formatAmount(tx.sourceAmount, tx.sourceCurrency)}</span>
            </DetailField>
            <DetailField label="Fee" value={formatAmount(tx.feeAmount, tx.sourceCurrency)} />
            <DetailField label="FX Rate" value={`1 ${tx.sourceCurrency} = ${tx.fxRate?.toFixed(8)} ${tx.destCurrency}`} />
            <DetailField label="Destination Amount">
              <span style={{ fontSize: 18, fontWeight: 700, color: '#166534' }}>
                {formatAmount(tx.destAmount, tx.destCurrency)}
              </span>
            </DetailField>
          </div>
        </div>
      </div>

      <div className="detail-grid">
        {/* Status Timeline */}
        <div className="card">
          <div className="card-title">Status Timeline</div>
          {tx.statusHistory?.length > 0 ? (
            <ul className="timeline">
              {tx.statusHistory.map((h, i) => (
                <li key={i} className="timeline-item">
                  <div className="timeline-dot" style={{
                    background: h.toStatus === 'completed' ? '#16a34a' :
                               h.toStatus === 'failed' ? '#dc2626' :
                               h.toStatus === 'processing' ? '#d97706' : '#4f46e5'
                  }} />
                  <div className="timeline-time">
                    {h.createdAt ? format(new Date(h.createdAt), 'PPpp') : ''}
                  </div>
                  <div className="timeline-label">
                    {h.fromStatus ? `${h.fromStatus} → ${h.toStatus}` : h.toStatus}
                  </div>
                  {h.reason && <div className="timeline-reason">{h.reason}</div>}
                </li>
              ))}
            </ul>
          ) : (
            <p style={{ color: '#9ca3af', fontSize: 14 }}>No status history available</p>
          )}
        </div>

        {/* Ledger Entries */}
        <div className="card">
          <div className="card-title">Ledger Entries</div>
          {tx.ledgerEntries?.length > 0 ? (
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <thead>
                <tr>
                  <th style={{ textAlign: 'left', padding: '6px 8px', color: '#6b7280', fontWeight: 600, fontSize: 11, textTransform: 'uppercase' }}>Account</th>
                  <th style={{ textAlign: 'left', padding: '6px 8px', color: '#6b7280', fontWeight: 600, fontSize: 11, textTransform: 'uppercase' }}>Type</th>
                  <th style={{ textAlign: 'right', padding: '6px 8px', color: '#6b7280', fontWeight: 600, fontSize: 11, textTransform: 'uppercase' }}>Amount</th>
                </tr>
              </thead>
              <tbody>
                {tx.ledgerEntries.map((e) => (
                  <tr key={e.id} style={{ borderBottom: '1px solid #f3f4f6' }}>
                    <td style={{ padding: '8px' }}>
                      <div style={{ fontWeight: 500 }}>{e.accountName}</div>
                      <div style={{ fontSize: 11, color: '#9ca3af' }}>{e.description}</div>
                    </td>
                    <td style={{ padding: '8px' }}>
                      <span className={`badge badge-${e.entryType === 'DEBIT' ? 'failed' : 'completed'}`}>
                        {e.entryType}
                      </span>
                    </td>
                    <td style={{ padding: '8px', textAlign: 'right' }}>
                      <span className={e.entryType === 'DEBIT' ? 'ledger-debit' : 'ledger-credit'}>
                        {e.entryType === 'DEBIT' ? '-' : '+'}{formatAmount(e.amount, e.currency)}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <p style={{ color: '#9ca3af', fontSize: 14 }}>No ledger entries yet</p>
          )}
        </div>
      </div>
    </div>
  );
}
