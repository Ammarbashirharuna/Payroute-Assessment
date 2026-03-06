import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { paymentsApi, accountsApi } from '../services/api';
import { v4 as uuidv4 } from 'uuid';

// Simulated FX rates matching backend
const FX_RATES = {
  'NGN-USD': 0.000647, 'NGN-GBP': 0.000511,
  'NGN-EUR': 0.000598, 'NGN-KES': 0.0836, 'NGN-GHS': 0.00983,
};
const FEE_RATE = 0.015;
const CURRENCIES = ['USD', 'EUR', 'GBP', 'KES', 'GHS'];

function computeQuote(sourceCurrency, destCurrency, sourceAmount) {
  const rate = FX_RATES[`${sourceCurrency}-${destCurrency}`];
  if (!rate || !sourceAmount || isNaN(sourceAmount)) return null;
  const fee = sourceAmount * FEE_RATE;
  const dest = sourceAmount * rate;
  return { rate, fee, dest, total: sourceAmount + fee };
}

export default function PaymentForm() {
  const navigate = useNavigate();
  const [accounts, setAccounts] = useState([]);
  const [form, setForm] = useState({
    senderAccountId: '',
    recipientName: '',
    recipientCountry: '',
    destCurrency: 'USD',
    sourceAmount: '',
    sourceCurrency: 'NGN',
  });
  const [quote, setQuote] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState(null);

  useEffect(() => {
    accountsApi.list().then(setAccounts).catch(() => {});
  }, []);

  // Recompute quote whenever relevant fields change
  useEffect(() => {
    const q = computeQuote(form.sourceCurrency, form.destCurrency, parseFloat(form.sourceAmount));
    setQuote(q);
  }, [form.sourceCurrency, form.destCurrency, form.sourceAmount]);

  const selectedAccount = accounts.find((a) => a.id === form.senderAccountId);
  const ngnBalance = selectedAccount?.balances?.find((b) => b.currency === 'NGN')?.balance;

  const handleChange = (key, value) => setForm((f) => ({ ...f, [key]: value }));

  const handleSubmit = async () => {
    if (!form.senderAccountId || !form.recipientName || !form.recipientCountry
        || !form.sourceAmount || !form.destCurrency) {
      setError('Please fill in all required fields.');
      return;
    }
    if (!quote) {
      setError('Unable to compute FX quote. Check the currency pair.');
      return;
    }

    setLoading(true);
    setError('');
    try {
      // Generate a unique idempotency key per submission attempt
      const idempotencyKey = uuidv4();
      const result = await paymentsApi.create(
        {
          senderAccountId: form.senderAccountId,
          recipientName: form.recipientName,
          recipientCountry: form.recipientCountry,
          destCurrency: form.destCurrency,
          sourceAmount: parseFloat(form.sourceAmount),
          sourceCurrency: form.sourceCurrency,
        },
        idempotencyKey
      );
      setSuccess(result);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  if (success) {
    return (
      <div>
        <div className="page-header">
          <h1>Payment Submitted ✓</h1>
        </div>
        <div className="card">
          <div style={{ textAlign: 'center', padding: '20px 0' }}>
            <div style={{ fontSize: 48, marginBottom: 12 }}>✅</div>
            <h2 style={{ marginBottom: 8 }}>Payment Initiated</h2>
            <p style={{ color: '#6b7280', marginBottom: 20 }}>
              Your payment is being processed.
            </p>
            <code style={{ background: '#f3f4f6', padding: '6px 12px', borderRadius: 6, fontSize: 14 }}>
              {success.reference}
            </code>
            <div style={{ marginTop: 24, display: 'flex', gap: 12, justifyContent: 'center' }}>
              <button className="btn btn-primary" onClick={() => navigate(`/payments/${success.id}`)}>
                View Transaction
              </button>
              <button className="btn btn-secondary" onClick={() => { setSuccess(null); setForm({ senderAccountId: '', recipientName: '', recipientCountry: '', destCurrency: 'USD', sourceAmount: '', sourceCurrency: 'NGN' }); }}>
                New Payment
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div>
      <div className="page-header">
        <h1>New Payment</h1>
        <p>Send funds across borders via PayRoute</p>
      </div>

      {error && <div className="error-box">{error}</div>}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
        {/* Source */}
        <div className="card">
          <div className="card-title">Sender Details</div>
          <div className="form-group">
            <label>Source Account *</label>
            <select
              value={form.senderAccountId}
              onChange={(e) => handleChange('senderAccountId', e.target.value)}
            >
              <option value="">Select account...</option>
              {accounts.map((a) => (
                <option key={a.id} value={a.id}>{a.ownerName}</option>
              ))}
            </select>
          </div>
          {selectedAccount && ngnBalance !== undefined && (
            <div style={{ background: '#f0f9ff', padding: '10px 12px', borderRadius: 8, fontSize: 13, color: '#0369a1', marginBottom: 16 }}>
              Available balance: <strong>{Number(ngnBalance).toLocaleString('en-NG', { minimumFractionDigits: 2 })} NGN</strong>
            </div>
          )}
          <div className="form-row">
            <div className="form-group">
              <label>Amount *</label>
              <input
                type="number"
                min="1"
                value={form.sourceAmount}
                onChange={(e) => handleChange('sourceAmount', e.target.value)}
                placeholder="0.00"
              />
            </div>
            <div className="form-group">
              <label>Currency</label>
              <select value={form.sourceCurrency} onChange={(e) => handleChange('sourceCurrency', e.target.value)}>
                <option value="NGN">NGN</option>
              </select>
            </div>
          </div>
        </div>

        {/* Recipient */}
        <div className="card">
          <div className="card-title">Recipient Details</div>
          <div className="form-group">
            <label>Recipient Name *</label>
            <input
              type="text"
              value={form.recipientName}
              onChange={(e) => handleChange('recipientName', e.target.value)}
              placeholder="Acme Suppliers Ltd"
            />
          </div>
          <div className="form-row">
            <div className="form-group">
              <label>Country Code *</label>
              <input
                type="text"
                maxLength={2}
                value={form.recipientCountry}
                onChange={(e) => handleChange('recipientCountry', e.target.value.toUpperCase())}
                placeholder="US"
              />
            </div>
            <div className="form-group">
              <label>Destination Currency *</label>
              <select value={form.destCurrency} onChange={(e) => handleChange('destCurrency', e.target.value)}>
                {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
            </div>
          </div>
        </div>
      </div>

      {/* Quote Preview */}
      {quote && form.sourceAmount && (
        <div className="quote-box">
          <h4>💱 FX Quote Preview (valid 5 minutes)</h4>
          <div className="quote-row">
            <span>Exchange Rate</span>
            <strong>1 {form.sourceCurrency} = {quote.rate.toFixed(8)} {form.destCurrency}</strong>
          </div>
          <div className="quote-row">
            <span>Transfer Amount</span>
            <strong>{parseFloat(form.sourceAmount).toLocaleString()} {form.sourceCurrency}</strong>
          </div>
          <div className="quote-row">
            <span>Fee (1.5%)</span>
            <strong>{quote.fee.toFixed(2)} {form.sourceCurrency}</strong>
          </div>
          <div className="quote-row">
            <span>Total Deducted</span>
            <strong>{quote.total.toFixed(2)} {form.sourceCurrency}</strong>
          </div>
          <div className="quote-row" style={{ borderTop: '1px solid #86efac', paddingTop: 8, marginTop: 8 }}>
            <span style={{ fontWeight: 700 }}>Recipient Gets</span>
            <strong style={{ fontSize: 16 }}>{quote.dest.toFixed(4)} {form.destCurrency}</strong>
          </div>
        </div>
      )}

      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
        <button className="btn btn-secondary" onClick={() => navigate('/')}>Cancel</button>
        <button
          className="btn btn-primary"
          onClick={handleSubmit}
          disabled={loading || !quote || !form.senderAccountId}
        >
          {loading ? 'Processing...' : 'Confirm & Send Payment'}
        </button>
      </div>
    </div>
  );
}
