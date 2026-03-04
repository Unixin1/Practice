package Backend;

/**
 *
 * @author Andrei
 */
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class BroadcastTask implements Runnable {
    private final String deviceName;
    private volatile boolean running = true;
    private final int port;
    private final long intervalMs;

    public BroadcastTask(String deviceName, int port, long intervalMs) {
        this.deviceName = deviceName;
        this.port = port;
        this.intervalMs = intervalMs;
    }

    public void stop() { running = false; }

    @Override
    public void run() {
        try {
            while (running) {
                DatagramSocket socket = new DatagramSocket();
                socket.setBroadcast(true);
                String payload = "DISCOVER:" + deviceName;
                byte[] data = payload.getBytes();
                DatagramPacket packet = new DatagramPacket(
                        data, data.length,
                        InetAddress.getByName("255.255.255.255"),
                        port
                );
                try {
                    socket.send(packet);
                } catch (Exception e) {
                    // ignore send problems (network down, etc.)
                } finally {
                    socket.close();
                }
                Thread.sleep(intervalMs);
            }
        } catch (InterruptedException e) {
            // thread was interrupted, exit
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

public class ClientInfo {
    private final String name;
    private final String ip;
    private volatile long lastSeen;

    public ClientInfo(String name, String ip) {
        this.name = name;
        this.ip = ip;
        this.lastSeen = System.currentTimeMillis();
    }

    public String getName() { return name; }
    public String getIp() { return ip; }

//    public long getLastSeen() { return lastSeen; }
//    public void touch() { lastSeen = System.currentTimeMillis(); }

    // expired if not seen for more than expiryMs milliseconds
    public boolean isExpired(long expiryMs) {
        return System.currentTimeMillis() - lastSeen > expiryMs;
    }

    @Override
    public String toString() {
        return name + " (" + ip + ")";
    }
}

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

public class ClientManager {
    // key: ip, value: ClientInfo
    private final ConcurrentHashMap<String, ClientInfo> clients = new ConcurrentHashMap<>();

    // Add or update a client (keeps lastSeen fresh)
    public void addOrUpdateClient(String name, String ip) {
        clients.compute(ip, (k, existing) -> {
            if (existing == null) {
                return new ClientInfo(name, ip);
            } 
//            else {
//                existing.touch();
//                return existing;
//            }
         return existing;
        });
    }

    public ClientInfo getClientByIp(String ip) {
        return clients.get(ip);
    }

    public Collection<ClientInfo> getAllClients() {
        return clients.values();
    }

    // Remove clients not seen for expiryMs milliseconds
    public void removeInactive(long expiryMs) {
        clients.forEach((ip, info) -> {
            if (info.isExpired(expiryMs)) clients.remove(ip);
        });
    }
}

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class ListenerTask implements Runnable {
    private final NetworkManager networkManager;
    private volatile boolean running = true;
    private final int port;

    public ListenerTask(NetworkManager networkManager, int port) {
        this.networkManager = networkManager;
        this.port = port;
    }

    public void stop() { running = false; }

    @Override
    public void run() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(port);
            socket.setSoTimeout(2000); // so we can check the running flag periodically
            byte[] buffer = new byte[2048];

            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);//blocks the thread
                } catch (java.net.SocketTimeoutException ste) {
                    continue; // loop again to check running
                }

                int len = packet.getLength();
                if (len <= 0) continue;
                String msg = new String(packet.getData(), 0, len);
                String senderIp = packet.getAddress().getHostAddress();
                networkManager.handleIncoming(senderIp, msg);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (socket != null && !socket.isClosed()) socket.close();
        }
    }
}

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NetworkManager {
    private final ClientManager clientManager;
    private  MessageListener uiListener; // callback to UI
    private final String deviceName;
    private final int port;
    private final ExecutorService executor;

    private BroadcastTask broadcaster;
    private ListenerTask listenerTask;

    // how long until a client is considered offline (milliseconds)
    private static final long CLIENT_EXPIRY_MS = 15_000L;

    public NetworkManager(ClientManager clientManager, MessageListener uiListener, String deviceName, int port) {
        this.clientManager = clientManager;
        this.uiListener = uiListener;
        this.deviceName = deviceName;
        this.port = port;
        this.executor = Executors.newSingleThreadExecutor(); // single thread executes tasks sequentially
    }

    public void start() {
        // Start the listener in its own runnable (still executed on the executor)
        listenerTask = new ListenerTask(this, port);
        executor.execute(listenerTask);

        // Start the broadcaster — run it on a separate thread using the same executor
        // Note: we use the same executor to keep code simple; the tasks are non-blocking due to sleeps
        broadcaster = new BroadcastTask(deviceName, port, 5000L);
        executor.execute(broadcaster);

        // Start a periodic pruning task (on same executor)
        executor.execute(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    clientManager.removeInactive(CLIENT_EXPIRY_MS);
                    uiListener.onClientListUpdated();
                    Thread.sleep(2000L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // announce presence immediately once
        broadcastNow();
    }

    public void stop() {
        if (broadcaster != null) broadcaster.stop();
        if (listenerTask != null) listenerTask.stop();
        executor.shutdownNow();
    }

    // send a discovery immediately
    public void broadcastNow() {
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setBroadcast(true);
            String payload = "DISCOVER:" + deviceName;
            byte[] data = payload.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), port);
            socket.send(packet);
            socket.close();
        } catch (Exception e) {
            // ignore
        }
    }

    // send direct chat message to a target IP
    public void sendMessage(String targetIp, String message) {
        try {
            DatagramSocket socket = new DatagramSocket();
            String payload = "CHAT:" + deviceName + ":" + message;
            byte[] data = payload.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName(targetIp), port);
            socket.send(packet);
            socket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Called by ListenerTask when a packet arrives
    public void handleIncoming(String senderIp, String message) {
        if (message == null || message.isEmpty()) return;

        if (message.startsWith("DISCOVER:")) {
            String name = message.substring("DISCOVER:".length());
            clientManager.addOrUpdateClient(name, senderIp);
            uiListener.onClientListUpdated();
        } else if (message.startsWith("CHAT:")) {
            // format: CHAT:senderName:actual message
            String[] parts = message.split(":", 3);
            if (parts.length >= 3) {
                String senderName = parts[1];
                String actual = parts[2];
                clientManager.addOrUpdateClient(senderName, senderIp);
                uiListener.onMessageReceived(senderName, senderIp, actual);
                uiListener.onClientListUpdated();
            }
        }
    }
}

public interface MessageListener {
    void onMessageReceived(String senderName, String senderIp, String message);
    void onClientListUpdated();
}


 public class Gikapoy extends javax.swing.JFrame implements MessageListener{
    private final ClientManager clientManager = new ClientManager();
    private NetworkManager networkManager;

    private final DefaultListModel<ClientInfo> listModel = new DefaultListModel<>();
    private final Map<String, Boolean> unreadMessages = new HashMap<>();
    private final Map<String, JTextArea> privateChats = new HashMap<>();

    private String currentChatKey = "GROUP";
    private final CardLayout cardLayout = new CardLayout();

    private static final int PORT = 5001;
        
    /**
     * Creates new form
     */
    public Gikapoy(String deviceName) {
        initComponents();
        setTitle("Disaster Mesh - " + deviceName);
    setLocationRelativeTo(null);

    
    

    setupCards();       // 🔥 IMPORTANT
        setupLogic(deviceName);
    }

    /* ================= CARD LAYOUT SETUP ================= */
    private void setupCards() {
        chatCards.setLayout(cardLayout);

        chatArea.setEditable(false);
        chatCards.add(new JScrollPane(chatArea), "GROUP");

        cardLayout.show(chatCards, "GROUP");
    }

    private void setupLogic(String deviceName) {

    networkManager = new NetworkManager(
            clientManager,
            this,
            deviceName,
            PORT
    );

    sendButton.addActionListener(e -> sendToCurrentChat());
    inputField.addActionListener(e -> sendToCurrentChat());

    clientList.setModel(listModel);

    // 🔴 Unread indicator renderer
    clientList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
        JLabel label = new JLabel(value.getName());
        if (unreadMessages.getOrDefault(value.getIp(), false)) {
            label.setText(value.getName() + "  🔴");
        }
        label.setOpaque(true);
        if (isSelected) label.setBackground(Color.LIGHT_GRAY);
        return label;
    });

    clientList.addListSelectionListener(e -> {
        if (e.getValueIsAdjusting()) return;

        ClientInfo ci = clientList.getSelectedValue();
        if (ci == null) return;

        currentChatKey = ci.getIp();
        unreadMessages.put(ci.getIp(), false); // clear unread
        clientList.repaint();

        if ("GROUP".equals(ci.getIp())) {
            chatTitle.setText("Group Chat");
            cardLayout.show(chatCards, "GROUP");
        } else {
            openPrivateChat(ci);
            chatTitle.setText("Chat with " + ci.getName());
        }
    });

    addWindowListener(new WindowAdapter() {
        @Override
        public void windowOpened(WindowEvent e) {
            statusLabel.setText("Status: running (port " + PORT + ")");
            networkManager.start();
        }

        @Override
        public void windowClosing(WindowEvent e) {
            networkManager.stop();
        }
    });
}


    /* ===== OPEN / CREATE PRIVATE CHAT ===== */
   private void openPrivateChat(ClientInfo ci) {
        String key = ci.getIp();

        if (!privateChats.containsKey(key)) {
            JTextArea area = new JTextArea();
            area.setEditable(false);
            privateChats.put(key, area);
            chatCards.add(new JScrollPane(area), key);
        }

        cardLayout.show(chatCards, key);
    }


    /* ===== SEND MESSAGE ===== */
    private void sendToCurrentChat() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        if ("GROUP".equals(currentChatKey)) {
            //chatArea.append("[Me] " + text + "\n");
        } else {
            JTextArea area = privateChats.get(currentChatKey);
            ClientInfo target = clientList.getSelectedValue();

            if (area != null && target != null) {
                networkManager.sendMessage(target.getIp(), text);
               area.append("Me: " + text + "\n");
            }
        }

        inputField.setText("");
    }

    // MessageListener implementation: invoked by NetworkManager on background threads.
    @Override
    public void onMessageReceived(String senderName, String senderIp, String msg) {
        SwingUtilities.invokeLater(() -> {

            if (!privateChats.containsKey(senderIp)) {
                JTextArea area = new JTextArea();
                area.setEditable(false);
                privateChats.put(senderIp, area);
                chatCards.add(new JScrollPane(area), senderIp);
            }

            JTextArea area = privateChats.get(senderIp);
            area.append(senderName + ": " + msg + "\n");

            // 🔴 mark unread ONLY if not current chat
            if (!senderIp.equals(currentChatKey)) {
                unreadMessages.put(senderIp, true);
                clientList.repaint();
            }
        });
    }


    
    public void onClientListUpdated() {
        SwingUtilities.invokeLater(() -> {
            listModel.clear();
            //listModel.addElement(new ClientInfo("Group Chat", "GROUP"));
            clientManager.getAllClients().forEach(listModel::addElement);
            
            
        });
    }


    // helper: append to chat area
    public void displayMessage(String who, String msg) {
        chatArea.append("[" + who + "] " + msg + "\n\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }
    
   

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">                          
    private void initComponents() {

        inputField = new javax.swing.JTextField();
        jScrollPane5 = new javax.swing.JScrollPane();
        clientList = new javax.swing.JList<>();
        sendButton = new javax.swing.JButton();
        statusLabel = new javax.swing.JLabel();
        chatTitle = new javax.swing.JLabel();
        chatCards = new javax.swing.JPanel();
        chatArea = new javax.swing.JTextArea();
        jButton1 = new javax.swing.JButton();
        jLabel1 = new javax.swing.JLabel();
        jLabel3 = new javax.swing.JLabel();
        jLabel2 = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setPreferredSize(new java.awt.Dimension(905, 735));
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent evt) {
                formWindowClosing(evt);
            }
            public void windowOpened(java.awt.event.WindowEvent evt) {
                formWindowOpened(evt);
            }
        });
        getContentPane().setLayout(null);

        inputField.setBorder(javax.swing.BorderFactory.createBevelBorder(javax.swing.border.BevelBorder.RAISED));
        inputField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                inputFieldActionPerformed(evt);
            }
        });
        getContentPane().add(inputField);
        inputField.setBounds(130, 540, 530, 40);

        clientList.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        clientList.setModel(listModel);
        clientList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        clientList.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                clientListMouseClicked(evt);
            }
        });
        jScrollPane5.setViewportView(clientList);

        getContentPane().add(jScrollPane5);
        jScrollPane5.setBounds(120, 140, 212, 390);

        sendButton.setText("Send");
        sendButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                sendButtonActionPerformed(evt);
            }
        });
        getContentPane().add(sendButton);
        sendButton.setBounds(680, 540, 102, 40);

        statusLabel.setBackground(new java.awt.Color(255, 255, 255));
        statusLabel.setFont(new java.awt.Font("Bahnschrift", 1, 18)); // NOI18N
        statusLabel.setForeground(new java.awt.Color(255, 255, 255));
        statusLabel.setText("Status: running (port " + PORT + ")");
        getContentPane().add(statusLabel);
        statusLabel.setBounds(290, 590, 310, 20);

        chatTitle.setBackground(new java.awt.Color(255, 255, 255));
        chatTitle.setFont(new java.awt.Font("Bahnschrift", 1, 18)); // NOI18N
        chatTitle.setForeground(new java.awt.Color(255, 255, 255));
        chatTitle.setText("Group");
        getContentPane().add(chatTitle);
        chatTitle.setBounds(350, 110, 210, 30);

        chatArea.setEditable(false);
        chatArea.setColumns(20);
        chatArea.setRows(5);
        chatArea.setBorder(javax.swing.BorderFactory.createBevelBorder(javax.swing.border.BevelBorder.RAISED));

        javax.swing.GroupLayout chatCardsLayout = new javax.swing.GroupLayout(chatCards);
        chatCards.setLayout(chatCardsLayout);
        chatCardsLayout.setHorizontalGroup(
            chatCardsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(chatCardsLayout.createSequentialGroup()
                .addComponent(chatArea, javax.swing.GroupLayout.DEFAULT_SIZE, 434, Short.MAX_VALUE)
                .addContainerGap())
        );
        chatCardsLayout.setVerticalGroup(
            chatCardsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(chatCardsLayout.createSequentialGroup()
                .addComponent(chatArea, javax.swing.GroupLayout.DEFAULT_SIZE, 384, Short.MAX_VALUE)
                .addContainerGap())
        );

        getContentPane().add(chatCards);
        chatCards.setBounds(340, 140, 440, 390);

        jButton1.setText("Exit");
        jButton1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton1ActionPerformed(evt);
            }
        });
        getContentPane().add(jButton1);
        jButton1.setBounds(770, 20, 76, 27);

        jLabel1.setBackground(new java.awt.Color(255, 255, 255));
        jLabel1.setFont(new java.awt.Font("Bahnschrift", 1, 18)); // NOI18N
        jLabel1.setForeground(new java.awt.Color(255, 255, 255));
        jLabel1.setText("Discovered Client");
        getContentPane().add(jLabel1);
        jLabel1.setBounds(120, 120, 180, 23);

        jLabel3.setIcon(new javax.swing.ImageIcon(getClass().getResource("/Image/title1.png"))); // NOI18N
        jLabel3.setText("jLabel3");
        getContentPane().add(jLabel3);
        jLabel3.setBounds(320, 0, 250, 80);

        jLabel2.setIcon(new javax.swing.ImageIcon(getClass().getResource("/Image/asds.png"))); // NOI18N
        jLabel2.setText("jLabel2");
        getContentPane().add(jLabel2);
        jLabel2.setBounds(0, 0, 900, 700);

        pack();
    }// </editor-fold>                        

    private void formWindowOpened(java.awt.event.WindowEvent evt) {                                  
        // TODO add your handling code here:
        networkManager.start();
    }                                 

    private void formWindowClosing(java.awt.event.WindowEvent evt) {                                   
        // TODO add your handling code here:
        networkManager.stop();
    }                                  

    private void inputFieldActionPerformed(java.awt.event.ActionEvent evt) {                                           
        // TODO add your handling code here:
    }                                          

    private void clientListMouseClicked(java.awt.event.MouseEvent evt) {                                        
        // TODO add your handling code here:
    }                                       

    private void sendButtonActionPerformed(java.awt.event.ActionEvent evt) {                                           
        // TODO add your handling code here:
    }                                          

    private void jButton1ActionPerformed(java.awt.event.ActionEvent evt) {                                         
        // TODO add your handling code here:
        this.dispose();
        networkManager.stop();
        System.exit(0);
        
    }                                        

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        SwingUtilities.invokeLater(() -> {
        String deviceName;
        try {
            deviceName = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            deviceName = "Device-" + (int)(Math.random() * 10000);
        }
        new Gikapoy(deviceName).setVisible(true);
    });
    }

    // Variables declaration - do not modify                     
    private javax.swing.JTextArea chatArea;
    private javax.swing.JPanel chatCards;
    private javax.swing.JLabel chatTitle;
    private javax.swing.JList<ClientInfo> clientList;
    private javax.swing.JTextField inputField;
    private javax.swing.JButton jButton1;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JScrollPane jScrollPane5;
    private javax.swing.JButton sendButton;
    private javax.swing.JLabel statusLabel;
    // End of variables declaration             
    \

    import javax.swing.JOptionPane;

/**%
 *
 * @author Andrei
 */
public class StartupFrame extends javax.swing.JFrame {

    /**
     * Creates new form 
     */
    public StartupFrame() {
  
        initComponents();
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">                          
    private void initComponents() {

        nameField = new javax.swing.JTextField();
        jButton1 = new javax.swing.JButton();
        jLabel3 = new javax.swing.JLabel();
        jLabel4 = new javax.swing.JLabel();
        jLabel5 = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setPreferredSize(new java.awt.Dimension(372, 315));
        setResizable(false);
        setSize(new java.awt.Dimension(372, 315));
        getContentPane().setLayout(null);

        nameField.setFont(new java.awt.Font("Nirmala UI", 1, 18)); // NOI18N
        nameField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                nameFieldActionPerformed(evt);
            }
        });
        getContentPane().add(nameField);
        nameField.setBounds(30, 140, 290, 50);

        jButton1.setBackground(new java.awt.Color(0, 51, 51));
        jButton1.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        jButton1.setForeground(new java.awt.Color(255, 255, 255));
        jButton1.setText("Start Discovery");
        jButton1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton1ActionPerformed(evt);
            }
        });
        getContentPane().add(jButton1);
        jButton1.setBounds(110, 200, 130, 40);

        jLabel3.setIcon(new javax.swing.ImageIcon(getClass().getResource("/Image/title1.png"))); // NOI18N
        jLabel3.setText("jLabel3");
        getContentPane().add(jLabel3);
        jLabel3.setBounds(50, 20, 260, 100);

        jLabel4.setBackground(new java.awt.Color(255, 255, 255));
        jLabel4.setForeground(new java.awt.Color(255, 255, 255));
        jLabel4.setText("Username");
        getContentPane().add(jLabel4);
        jLabel4.setBounds(40, 120, 60, 16);

        jLabel5.setIcon(new javax.swing.ImageIcon(getClass().getResource("/Image/Startup.png"))); // NOI18N
        jLabel5.setText("jLabel5");
        getContentPane().add(jLabel5);
        jLabel5.setBounds(0, 0, 360, 280);

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>                        

    private void nameFieldActionPerformed(java.awt.event.ActionEvent evt) {                                          
        // TODO add your handling code here:
        
        
    }                                         

    private void jButton1ActionPerformed(java.awt.event.ActionEvent evt) {                                         
        // TODO add your handling code here:
        String name = nameField.getText();
        if(name.length() == 0)
        {
            JOptionPane.showMessageDialog(null, "Please put a username", "Error", JOptionPane.ERROR_MESSAGE);
        }
        else
        {
            Gikapoy nako = new Gikapoy(name);
            nako.setVisible(true);
            this.dispose();
            
        }
        
        
        
    }                                        

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html 
         */
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ClassNotFoundException ex) {
            java.util.logging.Logger.getLogger(ChatFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (InstantiationException ex) {
            java.util.logging.Logger.getLogger(ChatFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            java.util.logging.Logger.getLogger(ChatFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(ChatFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>
        //</editor-fold>

        /* Create and display the form */
        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                new ChatFrame().setVisible(true);
            }
        });
    }

    // Variables declaration - do not modify                     
    private javax.swing.JButton jButton1;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JTextField nameField;
    // End of variables declaration                   
}
