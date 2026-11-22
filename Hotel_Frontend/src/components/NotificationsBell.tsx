import { useCallback, useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import Dropdown from './Dropdown'
import { NotificationApi } from '../api/notifications'
import { useAuth } from '../store/auth'

const timeAgo = (value: string) => {
  const minutes = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 60000))
  if (minutes < 1) return 'Vừa xong'
  if (minutes < 60) return `${minutes} phút trước`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} giờ trước`
  return new Date(value).toLocaleDateString('vi-VN')
}

export default function NotificationsBell() {
  const user = useAuth((state) => state.user)
  const client = useQueryClient()
  const query = useQuery({
    queryKey: ['notifications', user?.id],
    queryFn: NotificationApi.listMine,
    enabled: !!user,
    refetchInterval: 60_000,
  })
  const refresh = useCallback(() => client.invalidateQueries({ queryKey: ['notifications', user?.id] }), [client, user?.id])
  useEffect(() => {
    window.addEventListener('hrs-notifications-updated', refresh)
    return () => window.removeEventListener('hrs-notifications-updated', refresh)
  }, [refresh])
  const readOne = useMutation({ mutationFn: NotificationApi.markRead, onSuccess: refresh })
  const readAll = useMutation({ mutationFn: NotificationApi.markAllRead, onSuccess: refresh })
  const items = query.data ?? []
  const unread = items.filter((item) => !item.readAt).length

  if (!user) return null

  return (
    <Dropdown align="right" label={
      <span className="notification-trigger" aria-label={`Thông báo${unread ? `, ${unread} chưa đọc` : ''}`}>
        <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M18 9a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" /></svg>
        {unread > 0 && <span className="notification-count">{unread > 9 ? '9+' : unread}</span>}
      </span>
    }>
      <div className="notification-panel">
        <div className="notification-heading">
          <b>Thông báo</b>
          {unread > 0 && <button type="button" onClick={() => readAll.mutate()}>Đọc tất cả</button>}
        </div>
        {query.isLoading && <p className="notification-empty">Đang tải thông báo...</p>}
        {query.error && <p className="notification-empty">Không tải được thông báo.</p>}
        {!query.isLoading && !query.error && items.length === 0 && <p className="notification-empty">Bạn chưa có thông báo nào.</p>}
        {items.slice(0, 8).map((item) => (
          <Link
            key={item.id}
            to={item.href}
            className={`notification-item ${item.readAt ? '' : 'unread'}`}
            onClick={() => { if (!item.readAt) readOne.mutate(item.id) }}
          >
            <span className={`notification-dot ${item.kind}`} />
            <span className="notification-copy">
              <b>{item.title}</b>
              <span>{item.message}</span>
              <small>{timeAgo(item.createdAt)}</small>
            </span>
          </Link>
        ))}
      </div>
    </Dropdown>
  )
}