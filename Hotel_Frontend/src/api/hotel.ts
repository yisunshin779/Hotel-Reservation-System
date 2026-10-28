import { api } from './client'
import type { User, Role } from '../types'

const USE_MOCK = true // đổi thành false khi backend đã có API đăng nhập

const MOCK_USERS: Record<string, { password: string; user: User }> = {
  'khach@example.com': { password: '12345678', user: { id: '1', fullName: 'Khách hàng', email: 'khach@example.com', role: 'guest' as Role } },
  'letan@example.com': { password: '12345678', user: { id: '2', fullName: 'Lễ tân', email: 'letan@example.com', role: 'receptionist' as Role } },
  'admin@example.com': { password: '12345678', user: { id: '3', fullName: 'Quản trị', email: 'admin@example.com', role: 'admin' as Role } },
}

export const AuthApi = {
  async login(email: string, password: string): Promise<{ token: string; user: User }> {
    if (USE_MOCK) {
      await new Promise((r) => setTimeout(r, 400))
      const found = MOCK_USERS[email]
      if (!found || found.password !== password) {
        throw new Error('Thông tin đăng nhập không đúng.')
      }
      return { token: 'mock-token', user: found.user }
    }
    const res = await api.post('/auth/login', { email, password })
    return res.data
  },
}