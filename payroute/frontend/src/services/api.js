import axios from 'axios';

const API_BASE = process.env.REACT_APP_API_URL || 'http://localhost:8080';

const api = axios.create({
  baseURL: API_BASE,
  headers: { 'Content-Type': 'application/json' },
});

// Response interceptor for consistent error handling
api.interceptors.response.use(
  (res) => res,
  (err) => {
    const message = err.response?.data?.error || err.message || 'Unknown error';
    return Promise.reject(new Error(message));
  }
);

export const paymentsApi = {
  list: (params) => api.get('/payments', { params }).then((r) => r.data),

  get: (id) => api.get(`/payments/${id}`).then((r) => r.data),

  create: (payload, idempotencyKey) =>
    api
      .post('/payments', payload, {
        headers: { 'Idempotency-Key': idempotencyKey },
      })
      .then((r) => r.data),
};

export const accountsApi = {
  list: () => api.get('/accounts').then((r) => r.data),
};
