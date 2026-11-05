export const vnd = (n: number) => n.toLocaleString('vi-VN') + 'đ'

export const today = () => new Date().toISOString().slice(0, 10)

export const nightsBetween = (a: string, b: string) =>
  Math.max(0, Math.round((+new Date(b) - +new Date(a)) / 86400000))