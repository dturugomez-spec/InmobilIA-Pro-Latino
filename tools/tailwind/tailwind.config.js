const P = __dirname + '/../../src/main';
const colores = ['amber','blue','cyan','emerald','green','indigo','pink','purple','slate'];
module.exports = {
  content: [P + '/resources/templates/**/*.html', P + '/resources/static/js/**/*.js', P + '/java/**/*.java'],
  // Clases que las páginas de funcionalidades construyen al vuelo (bg-${color}-100 / text-${color}-700)
  safelist: colores.flatMap(c => [`bg-${c}-100`, `text-${c}-700`]),
  darkMode: 'class',
  theme: { extend: {
    fontFamily: { sans: ['Inter', 'system-ui', 'sans-serif'], mono: ['Geist Mono', 'JetBrains Mono', 'monospace'] },
    colors: { brand: { 50:'#eff6ff',100:'#dbeafe',200:'#bfdbfe',300:'#93c5fd',400:'#60a5fa',500:'#3b82f6',600:'#2563eb',700:'#1d4ed8',800:'#1e40af',900:'#1e3a8a',950:'#172554' } },
    boxShadow: {
      'card': '0 1px 2px 0 rgb(0 0 0 / 0.05), 0 1px 3px 0 rgb(0 0 0 / 0.06)',
      'card-lg': '0 4px 6px -1px rgb(0 0 0 / 0.07), 0 2px 4px -2px rgb(0 0 0 / 0.05)',
      'card-xl': '0 10px 15px -3px rgb(0 0 0 / 0.08), 0 4px 6px -4px rgb(0 0 0 / 0.05)',
      'glow': '0 0 0 4px rgb(59 130 246 / 0.15)'
    },
    animation: { 'fade-in': 'fadeIn 0.25s ease-out', 'slide-up': 'slideUp 0.3s ease-out' }
  } }
};
