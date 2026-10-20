#!/bin/bash
# Script para subir InmobilIA Pro a GitHub
# Uso: bash upload-to-github.sh TU_USUARIO_GITHUB

set -e

if [ -z "$1" ]; then
    echo "❌ Uso: bash upload-to-github.sh TU_USUARIO_GITHUB"
    exit 1
fi

USER=$1
REPO="inmobilia-pro"

echo "🚀 Subiendo InmobilIA Pro a github.com/$USER/$REPO..."

# Verificar git instalado
if ! command -v git &> /dev/null; then
    echo "❌ Git no instalado. Descárgalo de https://git-scm.com"
    exit 1
fi

# Crear .gitignore si no existe
if [ ! -f .gitignore ]; then
cat > .gitignore <<'EOF'
# Maven
target/
.mvn/
mvnw
mvnw.cmd

# IDE
.idea/
.vscode/
*.iml
.classpath
.project
.settings/

# Logs
*.log
logs/

# Secretos
.env
.env.local
application-local.properties
application-secrets.properties

# OS
.DS_Store
Thumbs.db

# Build
*.class
out/
build/

# Mobile
mobile-app/node_modules/
mobile-app/android/
mobile-app/ios/
EOF
    echo "✅ .gitignore creado"
fi

# Init git si no existe
if [ ! -d .git ]; then
    git init
    echo "✅ Git inicializado"
fi

# Add + commit
git add .
git commit -m "InmobilIA Pro v1.0 - 110 sprints completados" || echo "⚠️ Sin cambios para commit"

# Set branch main
git branch -M main

# Set remote
git remote remove origin 2>/dev/null || true
git remote add origin "https://github.com/$USER/$REPO.git"

echo ""
echo "⚠️  AHORA:"
echo "1. Ve a https://github.com/new"
echo "2. Crea repo PRIVADO llamado: $REPO"
echo "3. NO marques 'Initialize with README'"
echo "4. Pulsa ENTER cuando esté creado..."
read

# Push
echo "📤 Pushing..."
git push -u origin main

echo ""
echo "✅ ¡Repositorio subido!"
echo "🌐 https://github.com/$USER/$REPO"
echo ""
echo "Siguiente paso: conecta Render.com con este repo (ver DEPLOY.md)"
