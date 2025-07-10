FROM python:3.9.12-buster

ARG PANDOC_ARCH
ENV PANDOC_ARCH=$PANDOC_ARCH

RUN apt-get update && apt-get install -y \
    latexmk \
    texlive-fonts-recommended  \
    texlive-latex-extra \
    texlive-font-utils \
    && rm -rf /var/lib/apt/lists/*

RUN wget "https://github.com/jgm/pandoc/releases/download/2.18/pandoc-2.18-1-${PANDOC_ARCH}.deb" && \
    dpkg -i "pandoc-2.18-1-${PANDOC_ARCH}.deb"

RUN pip install rdm[github]

WORKDIR /dhf

CMD make
