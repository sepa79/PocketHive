const net = require('net');

let connCount = 0;
let msgCount = 0;

const server = net.createServer((socket) => {
  connCount++;
  let buffer = Buffer.alloc(0);
  
  socket.on('data', (data) => {
    buffer = Buffer.concat([buffer, data]);
    
    let processed = true;
    while (processed) {
      processed = false;
      const bufferStr = buffer.toString();
      
      let endTag, endIdx;
      if ((endIdx = bufferStr.indexOf('</Document>')) !== -1) {
        endTag = '</Document>';
        endIdx += endTag.length;
      } else if ((endIdx = bufferStr.indexOf('\n')) !== -1) {
        endTag = '\n';
        endIdx += 1;
      } else {
        break;
      }
      
      const message = bufferStr.substring(0, endIdx - endTag.length);
      buffer = buffer.slice(endIdx);
      processed = true;
      msgCount++;
      
      // Fast response without logging
      if (message.includes('AUTH')) {
        socket.write(`AUTH_APPROVED: ${Date.now()}${endTag}`);
      } else if (message.includes('ECHO')) {
        socket.write(`ECHO_RESPONSE: ${message}${endTag}`);
      } else if (!message.includes('FIRE_FORGET')) {
        socket.write(`RESPONSE: ${message}${endTag}`);
      }
    }
  });
  
  socket.on('end', () => connCount--);
  socket.on('error', () => connCount--);
});

server.maxConnections = 1000;
server.listen(8080, () => {
  console.log('High-performance TCP mock server on port 8080');
  setInterval(() => {
    console.log(`Connections: ${connCount}, Messages: ${msgCount}`);
    msgCount = 0;
  }, 5000);
});