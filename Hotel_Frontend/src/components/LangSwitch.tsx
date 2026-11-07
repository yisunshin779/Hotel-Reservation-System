import { useTranslation } from 'react-i18next'

export default function LangSwitch() {
  const { i18n } = useTranslation()
  return (
    <select
      style={{ width: 'auto' }}
      value={i18n.language}
      onChange={(e) => {
        i18n.changeLanguage(e.target.value)
        localStorage.setItem('lng', e.target.value)
      }}
    >
      <option value="vi">VI</option>
      <option value="en">EN</option>
    </select>
  )
}