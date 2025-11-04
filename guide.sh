# Set up SSH keys
ssh-keygen -t rsa -b 4096 -f witec_vm

# Create VM on Azure
./helpers/make_vm.sh

# Make wallet and fund it

# make .env

# set up termius with a VM

# do hardhat

# On local machine (not on VM)
git clone git@github.com:eternal-dissident/witec-demo.git
cd witec-demo
# Here you need npm installed as a dependency
npm install

npx hardhat init
#replace hardhat.config.js with the one in the repo; add scripts/deploy.js
npx hardhat compile
npx hardhat run scripts/deploy.js --network sepolia

# check deploy on block explorer
# update gateway/server.py with contract address

# on the VM (via Termius or ssh)
sudo apt update && sudo apt upgrade -y
sudo apt install python3 python3-pip git curl nginx certbot python3-certbot-nginx -y
python3 --version
pip3 install flask web3 python-dotenv
mkdir ~/witec-demo && cd ~/witec-demo

# copy server.py, .env, TimberDemo.json, flask-app.service, witec-demo-nginx from local to VM

# check default nginx landing
# point the domain to IP
# check http domain


# Set up SSL certificates
sudo nano /etc/nginx/sites-available/default # replace "server_name _;" with "server_name witec.io;"
sudo systemctl restart nginx
sudo certbot --nginx -d witec.io
sudo systemctl restart nginx
sudo nginx -t

# Set up and start the Flask server
sudo cp flask-app.service /etc/systemd/system/flask-app.service
sudo mkdir -p /var/log/flask-app
sudo chown az:az /var/log/flask-app
sudo systemctl daemon-reload
sudo systemctl enable flask-app
sudo systemctl start flask-app

# Configure Nginx as a reverse proxy
sudo rm /etc/nginx/sites-enabled/default
sudo cp witec-demo-nginx /etc/nginx/sites-available/witec-demo
sudo ln -s /etc/nginx/sites-available/witec-demo /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl restart nginx

# journalctl -u flask-app -f