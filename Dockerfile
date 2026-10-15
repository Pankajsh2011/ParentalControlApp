FROM node:20-alpine

WORKDIR /app

# Copy package files from server directory
COPY server/package*.json ./
RUN npm install --production

# Copy server application files
COPY server/ ./

# Expose relay port
EXPOSE 5050

CMD ["node", "server.js"]
