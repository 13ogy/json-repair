# Image Docker pour la reproduction des expériences de json-repair.

FROM python:3.11-slim

# Outils système nécessaires à scala-cli (curl, JDK) et au runtime Java.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        curl ca-certificates default-jdk git \
 && rm -rf /var/lib/apt/lists/*

# Installation de scala-cli via le script officiel (binaire dans coursier/bin).
RUN curl -sSLf https://scala-cli.virtuslab.org/get \
        | sh -s -- --quiet \
 && /root/.local/share/coursier/bin/scala-cli --version
ENV PATH="/root/.local/share/coursier/bin:${PATH}"

WORKDIR /workspace

# Installation des dépendances Python dans .venv (cohérent avec les scripts Scala
# qui appellent .venv/bin/python3 explicitement).
COPY requirements.txt .
RUN python -m venv .venv \
 && .venv/bin/pip install --no-cache-dir --upgrade pip \
 && .venv/bin/pip install --no-cache-dir -r requirements.txt

# Copie du code source (les volumes du compose remplaceront ces fichiers
# en développement, mais l'image autonome reste utilisable).
COPY src/ src/
COPY scripts/ scripts/

# Les répertoires data/ et report/ sont créés vides ; les sous-modules de test
# (jsonschemabench, json-schema-test-suite) doivent être initialisés depuis
# l'hôte avec `git submodule update --init --recursive`.
RUN mkdir -p data report/img

CMD ["bash"]
