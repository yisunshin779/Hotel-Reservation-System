import axios from 'axios'
import { useAuth } from '../store/auth'

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api',
  timeout: 15000,
})

api.interceptors.request.use((config) => {
  const token = useAuth.getState().token
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

api.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) useAuth.getState().logout()
    return Promise.reject(
      new Error(err.response?.data?.message ?? 'Không kết nối được máy chủ.')
    )
  }
)