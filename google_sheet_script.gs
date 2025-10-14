// Google Apps Script to receive YOLO detections
// Deploy as Web App: Deploy > New deployment > Web app

function doPost(e) {
  try {
    const data = JSON.parse(e.postData.contents);
    const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName('Detections');
    
    // If sheet doesn't exist, create it with headers
    if (!sheet) {
      createSheet();
      return doPost(e);
    }
    
    // Add row with data
    sheet.appendRow([
      data.detectionId,
      data.className,
      data.classId,
      data.confidence,
      data.date,
      data.time,
      data.timestamp,
      data.coordinates.x,
      data.coordinates.y,
      data.coordinates.width,
      data.coordinates.height,
      data.inferenceTimeMs,
      data.deviceId,
      data.alertType,
      new Date().toISOString() // Server timestamp
    ]);
    
    // Apply conditional formatting based on alertType
    const lastRow = sheet.getLastRow();
    const alertCell = sheet.getRange(lastRow, 14); // Alert Type column
    
    if (data.alertType === 'HIGH_CONFIDENCE') {
      alertCell.setBackground('#d4edda'); // Light green
    } else if (data.alertType === 'LOW_CONFIDENCE') {
      alertCell.setBackground('#f8d7da'); // Light red
    }
    
    return ContentService.createTextOutput(JSON.stringify({
      status: 'success',
      message: 'Data saved successfully'
    })).setMimeType(ContentService.MimeType.JSON);
    
  } catch (error) {
    return ContentService.createTextOutput(JSON.stringify({
      status: 'error',
      message: error.toString()
    })).setMimeType(ContentService.MimeType.JSON);
  }
}

function doGet(e) {
  return ContentService.createTextOutput('YOLO Detections API is working correctly');
}

function createSheet() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sheet = ss.insertSheet('Detections');
  
  // Create headers
  const headers = [
    'Detection ID',
    'Class',
    'Class ID',
    'Confidence (%)',
    'Date',
    'Time',
    'Timestamp',
    'X',
    'Y',
    'Width',
    'Height',
    'Inference Time (ms)',
    'Device ID',
    'Alert Type',
    'Server Timestamp'
  ];
  
  sheet.getRange(1, 1, 1, headers.length).setValues([headers]);
  
  // Format headers
  const headerRange = sheet.getRange(1, 1, 1, headers.length);
  headerRange.setBackground('#4285f4');
  headerRange.setFontColor('#ffffff');
  headerRange.setFontWeight('bold');
  
  // Auto-resize columns
  sheet.autoResizeColumns(1, headers.length);
  
  // Freeze header row
  sheet.setFrozenRows(1);
}

// Function to clean old data (optional)
function cleanOldData() {
  const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName('Detections');
  const daysToKeep = 30; // Keep last 30 days
  
  if (!sheet) return;
  
  const data = sheet.getDataRange().getValues();
  const cutoffDate = new Date();
  cutoffDate.setDate(cutoffDate.getDate() - daysToKeep);
  
  for (let i = data.length - 1; i > 0; i--) { // Start from bottom
    const rowDate = new Date(data[i][6]); // Timestamp column
    if (rowDate < cutoffDate) {
      sheet.deleteRow(i + 1);
    }
  }
}