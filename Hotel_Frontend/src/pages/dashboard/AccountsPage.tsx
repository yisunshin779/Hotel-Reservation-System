import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AdminAccountsApi, USE_MOCK_AUTH } from '../../api/hotel'
import { useAuth } from '../../store/auth'

const ROLES = [
  { value: 'ROLE_USER', label: 'Khách hàng' },
  { value: 'ROLE_RECEPTIONIST', label: 'Lễ tân' },
  { value: 'ROLE_ADMIN', label: 'Quản trị viên' },
]
const STATUSES = [
  { value: 'DA_KICH_HOAT', label: 'Đang hoạt động' },
  { value: 'BI_KHOA', label: 'Đã khóa' },
]

const roleLabel = (role: string) => ROLES.find((item) => item.value === role)?.label ?? role
const statusLabel = (status: string) =>
  status === 'DA_KICH_HOAT' ? 'Đang hoạt động' : status === 'BI_KHOA' ? 'Đã khóa' : 'Chờ xác thực'

export default function AccountsPage() {
  const queryClient = useQueryClient()
  const currentUser = useAuth((state) => state.user)
  const [search, setSearch] = useState('')
  const accounts = useQuery({
    queryKey: ['admin-accounts'],
    queryFn: AdminAccountsApi.list,
    enabled: !USE_MOCK_AUTH,
  })
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['admin-accounts'] })
  const updateRole = useMutation({
    mutationFn: ({ id, value }: { id: string; value: string }) => AdminAccountsApi.updateRole(id, value),
    onSuccess: refresh,
  })
  const updateStatus = useMutation({
    mutationFn: ({ id, value }: { id: string; value: string }) => AdminAccountsApi.updateStatus(id, value),
    onSuccess: refresh,
  })

  const shown = useMemo(() => {
    const term = search.trim().toLocaleLowerCase('vi')
    return (accounts.data ?? []).filter((account) =>
      `${account.hoTen} ${account.email} ${account.soDienThoai ?? ''}`.toLocaleLowerCase('vi').includes(term)
    )
  }, [accounts.data, search])

  if (USE_MOCK_AUTH) {
    return <>
      <h1>Quản lý tài khoản</h1>
      <div className="card">
        Để dùng chức năng này, hãy đặt <code>VITE_USE_MOCK_AUTH=false</code> trong file <code>.env</code>, khởi động lại frontend và đăng nhập bằng tài khoản admin của backend.
      </div>
    </>
  }

  return <>
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
      <div>
        <h1 style={{ marginBottom: 4 }}>Quản lý tài khoản</h1>
        <p className="mut" style={{ marginTop: 0 }}>Phân quyền và quản lý trạng thái tài khoản trong hệ thống.</p>
      </div>
      <button className="btn" onClick={() => refresh()} disabled={accounts.isFetching}>Làm mới</button>
    </div>

    <label className="search">
      Tìm theo tên, email hoặc số điện thoại
      <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Nhập từ khóa..." />
    </label>

    {(updateRole.error || updateStatus.error) && (
      <p className="err">{((updateRole.error ?? updateStatus.error) as Error).message}</p>
    )}
    {accounts.isLoading && <p>Đang tải danh sách tài khoản...</p>}
    {accounts.error && <p className="err">{(accounts.error as Error).message}</p>}
    {!accounts.isLoading && !accounts.error && shown.length === 0 && (
      <div className="card">{search ? 'Không tìm thấy tài khoản phù hợp.' : 'Chưa có tài khoản nào.'}</div>
    )}

    {!!shown.length && <div className="card wrap">
      <table>
        <thead><tr>
          <th>Tài khoản</th><th>Liên hệ</th><th>Vai trò</th><th>Trạng thái</th><th>Ngày tạo</th>
        </tr></thead>
        <tbody>{shown.map((account) => {
          const isSelf = account.email.toLowerCase() === currentUser?.email.toLowerCase()
          const rowPending = updateRole.isPending || updateStatus.isPending
          return <tr key={account.id}>
            <td><b>{account.hoTen}</b><br /><small className="mut">{account.email}</small></td>
            <td>{account.soDienThoai || '—'}<br /><small className="mut">{account.authProvider}</small></td>
            <td>
              <select aria-label={`Vai trò của ${account.hoTen}`} value={account.vaiTro}
                disabled={isSelf || rowPending}
                onChange={(event) => {
                  const value = event.target.value
                  if (confirm(`Đổi vai trò của ${account.hoTen} thành “${roleLabel(value)}”?`)) {
                    updateRole.mutate({ id: account.id, value })
                  } else refresh()
                }}>
                {!ROLES.some((role) => role.value === account.vaiTro) && <option value={account.vaiTro}>{roleLabel(account.vaiTro)}</option>}
                {ROLES.map((role) => <option key={role.value} value={role.value}>{role.label}</option>)}
              </select>
            </td>
            <td>
              <select aria-label={`Trạng thái của ${account.hoTen}`} value={account.trangThai}
                disabled={isSelf || rowPending}
                onChange={(event) => {
                  const value = event.target.value
                  if (confirm(`${value === 'BI_KHOA' ? 'Khóa' : 'Mở khóa'} tài khoản ${account.hoTen}?`)) {
                    updateStatus.mutate({ id: account.id, value })
                  } else refresh()
                }}>
                {!STATUSES.some((status) => status.value === account.trangThai) && <option value={account.trangThai}>{statusLabel(account.trangThai)}</option>}
                {STATUSES.map((status) => <option key={status.value} value={status.value}>{status.label}</option>)}
              </select>
            </td>
            <td>{new Date(account.thoiGianTao).toLocaleDateString('vi-VN')}</td>
          </tr>
        })}</tbody>
      </table>
      {shown.some((account) => account.email.toLowerCase() === currentUser?.email.toLowerCase()) &&
        <small className="mut">Không thể tự thay đổi vai trò hoặc trạng thái của tài khoản đang đăng nhập.</small>}
    </div>}
  </>
}
